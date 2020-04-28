(ns jepsen.rabbitmq
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.string :as str]
            [clojure.java.io       :as io]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util :refer [timeout]]
                    [core           :as core]
                    [util           :refer [meh timeout log-op]]
                    [codec          :as codec]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [knossos.core          :as knossos]
            [knossos.op            :as op])
  (:import (com.rabbitmq.client AlreadyClosedException
                                ShutdownSignalException)))

(def erlang-version "1:22.3.*")

(defn db
  []
  (reify db/DB
    (setup! [_ test node]
      (c/cd "/tmp"
            (info "Deleting rabbitmq")
            (c/exec :rm :-rf "/tmp/rabbitmq*")

            (c/su
              (c/exec* "killall -q -9 'beam.smp' 'epmd' || true")
              (try (c/exec* "erl -noshell -eval \"\\$2 /= hd(erlang:system_info(otp_release)) andalso halt(2).\" -run init stop")
                    (catch Exception e
                      (info "Erlang not detected, installing it...")
                      (c/exec :echo "deb https://dl.bintray.com/rabbitmq-erlang/debian stretch erlang" :> "/etc/apt/sources.list.d/rabbitmq-erlang.list")
                      (info "downloading RabbitMQ repository signature")
                      (let [signature_file (cu/wget! "http://www.rabbitmq.com/rabbitmq-release-signing-key.asc")]
                        (c/exec :apt-key :add signature_file))
                      ; pin Erlang version  
                      (c/exec :mkdir :-p "/etc/apt/preferences.d/")
                      (c/exec :echo (-> "rabbitmq/erlang"
                                    io/resource
                                    slurp
                                    (str/replace "$ERLANG_VERSION" erlang-version))
                          :> "/etc/apt/preferences.d/erlang")  
                      (info "apt-update")
                      (debian/update!)
                      (info "Installing Erlang")
                      (debian/install [:socat :erlang-nox]
                                      )))

              (info "Downloading RabbitMQ " (test :archive-url))
              (cu/install-archive! (str (test :archive-url)) "/tmp/rabbitmq-server")

              ; Update config
              (c/exec :echo (-> "rabbitmq/rabbitmq.conf"
                                io/resource
                                slurp)
                      :> "/tmp/rabbitmq-server/etc/rabbitmq/rabbitmq.conf")
              (c/exec :echo (-> "rabbitmq/advanced.config"
                                io/resource
                                slurp
                                (str/replace "$NET_TICKTIME" (str (test :net-ticktime))))
                      :> "/tmp/rabbitmq-server/etc/rabbitmq/advanced.config")
              (info "setting Erlang cookie")
              (c/exec :echo "jepsen-rabbitmq"
                      :> "/root/.erlang.cookie")
              (c/exec :chmod :600 "/root/.erlang.cookie")        
              
              ; Start broker on first node
              (let [p (core/primary test)]
                (if (= node p)
                  (do
                    (info "Starting RabbitMQ on first node")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmq-server -detached")
                  )
                )
              )
              ; wait for the primary to come up
              (Thread/sleep 5000)
              (core/synchronize test)
              ; start the remaining nodes
              (let [p (core/primary test)]
                (if-not (= node p)
                  (do
                    (info "Starting RabbitMQ")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmq-server -detached")
                    (info "Waiting for 5 seconds")
                    (Thread/sleep 5000)
                    (info "Stopping app")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl stop_app")    
                    (info "Join cluster" (str "rabbit@" p))
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl" "join_cluster" (str "rabbit@" p))    
                    (info "Starting app")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl start_app")
                    (info "App started")
                  )
                )
              )
              )))



  (teardown! [_ test node]
             (c/su
               ; there is no real need to clear anything down here as we
               ; reset everything before each run
               (info node "Teardown complete")))
    db/LogFiles
    (log-files [_ test node]
      [
       (str "/tmp/rabbitmq-server/var/log/rabbitmq/rabbit@" node ".log")
       (str "/tmp/rabbitmq-server/var/log/rabbitmq/log/crash.log")
       ] )))

(def queue "jepsen.queue")

(defn dequeue!
  "Given a client and an operation, dequeues a value and returns the
  corresponding operation."
  [conn op]
    (try
      (let [result (com.rabbitmq.jepsen.Utils/dequeue conn)]
            (if (= nil result)
              (assoc op :type :fail :value :exhausted)
              (assoc op :type :ok :value result))
      )
      (catch java.util.concurrent.TimeoutException _
          (info "dequeue timed out")
          (assoc op :type :fail :value :timeout))
    )
)

(defmacro with-ch
  "Opens a channel on 'conn for body, binds it to the provided symbol 'ch, and
  ensures the channel is closed after body returns."
  [[ch conn] & body]
  `(let [~ch (lch/open ~conn)]
     (try ~@body
          (finally
            (try (rmq/close ~ch)
                 (catch AlreadyClosedException _# (info "channel was already closed"))
                 )))))

(defrecord QueueClient
  [conn publish-confirm-timeout]
  client/Client
  (open! [client test node]
    (info "open! called for " node)
    (try
      (assoc client :conn (com.rabbitmq.jepsen.Utils/createClient test node)
                    :publish-confirm-timeout (test :publish-confirm-timeout)                    
                    )
    (catch Exception _ client))
    )
  (setup! [client test]
    (com.rabbitmq.jepsen.Utils/setup conn)
    client)

  (teardown! [_ test])
  ; there is nothing to tear down
  (close! [_ test]
      (meh (com.rabbitmq.jepsen.Utils/close conn))
  )

  (invoke! [client test op]
    
    (try 
        (case (:f op)
          :enqueue (do
                    (try
                      (if (com.rabbitmq.jepsen.Utils/enqueue conn (:value op) publish-confirm-timeout)
                        (assoc op :type :ok)
                        (assoc op :type :fail))
                    (catch java.util.concurrent.TimeoutException _ (assoc op :type :info :error :timeout)))
                  )

          :dequeue (dequeue! conn op)

          :drain (assoc op :type :ok, :value (com.rabbitmq.jepsen.Utils/drain conn))
        )
         (catch java.util.concurrent.TimeoutException _ 
          (info "channel operation timed out")
          (assoc op :type :info :error :timeout))
         (catch java.lang.Exception ex
          (info "unexpected client exception" (.getMessage ex) "reconnecting")
          (com.rabbitmq.jepsen.Utils/reconnect conn)
          (assoc op :type :fail :error :exception))
          )   
            ))

(defn queue-client [] (QueueClient. nil nil))

(def network-partition-nemeses
  "A map of network partition nemesis names"
  {"random-partition-halves"   ""
   "partition-halves"          ""
   "partition-majorities-ring" ""
   "partition-random-node"     ""
   })

(def consumer-types
  "A map of consumer types"
  {"asynchronous"  ""
   "polling"       ""
   "mixed"         ""
   })

(defn init-nemesis
      "Returns appropriate nemesis"
      [opts]
      (case (:network-partition opts)
            "random-partition-halves"   (nemesis/partition-random-halves)
            "partition-halves"          (nemesis/partition-halves)
            "partition-majorities-ring" (nemesis/partition-majorities-ring)
            "partition-random-node"     (nemesis/partition-random-node)
            )
      )

(defn rabbit-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (info :opts opts)
  (let [nemesis (init-nemesis opts)]
  (merge tests/noop-test
         {:name       "rabbitmq-simple-partition"
          :os         debian/os
          :db         (db)
          :client     (queue-client)
          :nemesis    nemesis
          :checker (checker/compose
                     {:perf   (checker/perf)
                      :queue (checker/total-queue)
                      ; :timeline (timeline/html)
                      })
          :generator  (gen/phases
                        (->> (gen/queue)
                             ; FIXME could gen/stagger introduce good randomness? 
                             (gen/delay (/ (:rate opts)))
                             (gen/nemesis
                               (gen/seq
                                 (cycle [(gen/sleep (:time-before-partition opts))
                                         {:type :info :f :start}
                                         (gen/sleep (:partition-duration opts))
                                         {:type :info :f :stop}])))
                             (gen/time-limit (:time-limit opts)))
                        (gen/nemesis
                          (gen/once {:type :info, :f :stop}))
                        (gen/log "waiting for recovery")
                        (gen/sleep 10)
                        (gen/clients
                          (gen/each
                            (gen/once {:type :invoke
                                       :f    :drain}))))}
         opts)
         ))

(defn parse-long
      "Parses a string to a Long. Passes through `nil`."
      [s]
      (when s (Long/parseLong s)))

(def cli-opts
  "Additional command line options."
  [
   ["-r" "--rate HZ" "Approximate number of enqueue/dequeue per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]] 
   [nil "--partition-duration NUM" "Duration of partition (in seconds)"
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--time-before-partition NUM" "Time before the partition starts (in seconds)"
    :default  10
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--archive-url URL" "URL to retrieve RabbitMQ Generic Unix archive"
    :default "https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.8.3/rabbitmq-server-generic-unix-3.8.3.tar.xz"
    :parse-fn read-string]
   [nil "--network-partition NAME" "Which network partition strategy to use. Default is random-partition-halves"
    :default  "random-partition-halves"
    :missing  (str "--network-partition " (cli/one-of network-partition-nemeses))
    :validate [network-partition-nemeses (cli/one-of network-partition-nemeses)]] 
   [nil "--publish-confirm-timeout NUM" "Timeout for publish confirms (in milliseconds)"
    :default  5000
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]
   [nil "--net-ticktime NUM" "Erlang net tick time in seconds (https://www.rabbitmq.com/nettick.html)."
    :default  15
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer."]]  
   [nil "--consumer-type TYPE" "Type of the consumers to dequeue and drain. Default is asynchronous"
    :default  "asynchronous"
    :missing  (str "--consumer-type " (cli/one-of consumer-types))
    :validate [consumer-types (cli/one-of consumer-types)]]  
   ])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rabbit-test, :opt-spec cli-opts})
            args))
