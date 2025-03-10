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

(def erlang-version "1:27*")

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
                      (c/exec :echo "deb https://ppa1.rabbitmq.com/rabbitmq/rabbitmq-erlang/deb/debian bookworm main" :>> "/etc/apt/sources.list.d/rabbitmq-erlang.list")
                      (c/exec :echo "deb https://ppa2.rabbitmq.com/rabbitmq/rabbitmq-erlang/deb/debian bookworm main" :>> "/etc/apt/sources.list.d/rabbitmq-erlang.list")
                      (info "downloading RabbitMQ repository signature")
                      (let [signature_file (cu/wget! "https://github.com/rabbitmq/signing-keys/releases/download/3.0/cloudsmith.rabbitmq-erlang.E495BB49CC4BBE5B.key")]
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
                      (debian/install [:socat :xz-utils :erlang-base :erlang-asn1 :erlang-crypto :erlang-eldap :erlang-ftp :erlang-inets :erlang-mnesia :erlang-os-mon :erlang-parsetools :erlang-public-key :erlang-runtime-tools :erlang-snmp :erlang-ssl :erlang-syntax-tools :erlang-tftp :erlang-tools :erlang-xmerl]
                                      )))

              (info "Downloading RabbitMQ " (test :archive-url))
              (c/exec :mkdir :-p "/tmp/rabbitmq-server")
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
              (Thread/sleep 15000)
              (let [p (core/primary test)]
                (if (= node p)
                  (do
                    (info "Activating Khepri on first node")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl enable_feature_flag --opt-in khepri_db")
                  )
                )
              )
              (core/synchronize test)
              ; start the remaining nodes
              (let [p (core/primary test)]
                (if-not (= node p)
                  (do
                    (info "Starting RabbitMQ")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmq-server -detached")
                    (info "Waiting for 20 seconds")
                    (Thread/sleep 20000)
                    (info "Activating Khepri")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl enable_feature_flag --opt-in khepri_db")
                    (info "Stopping app")
                    (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl stop_app")    
                    (info "Stopped app")
                    (Thread/sleep (rand-int 15000))
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
            (if (cu/exists? "/tmp/rabbitmq-server/sbin/rabbitmqctl")
             (do (c/su
               (let [ status (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl eval \"case whereis('%2F_jepsen.queue') of undefined -> no_local_member; _ -> sys:get_status(whereis('%2F_jepsen.queue')) end.\"")]
                                      (info "Quorum Member Status for 'jepsen.queue': " status))
               (let [ status (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl eval \"case whereis('%2F_jepsen.queue.dead.letter') of undefined -> no_local_member; _ -> sys:get_status(whereis('%2F_jepsen.queue.dead.letter')) end.\"")]
                                      (info "Quorum Member Status for 'jepsen.queue.dead.letter': " status))
               (let [ status (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl eval \"try supervisor:which_children(rabbit_fifo_dlx_sup) of [] -> no_local_dlx_worker; [{undefined, Pid, worker, _}] -> sys:get_status(Pid) catch exit:{noproc, _} -> no_dlx_sup end.\"")]
                                      (info "Status for rabbit_fifo_dlx_worker: " status))))
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
      (assoc client :conn (com.rabbitmq.jepsen.Utils/createClient test node)
                    :publish-confirm-timeout (test :publish-confirm-timeout)                    
                    )
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

(def enqueue-value (atom -1))
(defn enqueue   [_ _]
  {:type :invoke, :f :enqueue, :value (swap! enqueue-value inc)})
(defn dequeue   [_ _] {:type :invoke, :f :dequeue})

(defn rabbit-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (info :opts opts)
  (let [nemesis (init-nemesis opts)]
  (merge tests/noop-test
         {:pure-generators true
          :name       "rabbitmq-simple-partition"
          :os         debian/os
          :db         (db)
          :client     (queue-client)
          :nemesis    nemesis
          :checker (checker/compose
                     {:perf   (checker/perf)
                      :queue (checker/total-queue)
                      })
          :generator  (gen/phases
                        (->> (gen/mix [enqueue dequeue])
                             ; FIXME could gen/stagger introduce good randomness? 
                             (gen/delay (/ (:rate opts)))
                             (gen/nemesis
                                 (cycle [(gen/sleep (:time-before-partition opts))
                                         {:type :info :f :start}
                                         (gen/sleep (:partition-duration opts))
                                         {:type :info :f :stop}]))
                             (gen/time-limit (:time-limit opts)))
                        (gen/nemesis
                          (gen/once {:type :info, :f :stop}))
                        (gen/log "waiting for recovery")
                        (gen/sleep 20)
                        (gen/clients
                          (gen/each-thread
                            (gen/once {:type :invoke
                                       :f    :drain}))))}
         opts)
         ))

(def cli-opts
  "Additional command line options."
  [
   ["-r" "--rate HZ" "Approximate number of enqueue/dequeue per second, per thread."
    :default  50
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
    :default "https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.0.7/rabbitmq-server-generic-unix-4.0.7.tar.xz"
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
   [nil "--dead-letter FLAG" "Use dead letter queue and TTL on messages"
    :default false]
   [nil "--quorum-initial-group-size NUM" "Quorum queue cluster size (default is the number of cluster nodes)"
    :default  0
    :parse-fn parse-long]
   ])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rabbit-test, :opt-spec cli-opts})
            args))
