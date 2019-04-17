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
            [knossos.op            :as op]
            [langohr.core          :as rmq]
            [langohr.channel       :as lch]
            [langohr.confirm       :as lco]
            [langohr.queue         :as lq]
            [langohr.exchange      :as le]
            [langohr.basic         :as lb])
  (:import (com.rabbitmq.client AlreadyClosedException
                                ShutdownSignalException)))

(defn db
  []
  (reify db/DB
    (setup! [_ test node]
      (c/cd "/tmp"
            (info "Deleting rabbitmq")
            (c/exec :rm :-rf "/tmp/rabbitmq*")

            (let [uri "https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.8.0-beta.3/rabbitmq-server-generic-unix-3.8.0-beta.3.tar.xz" ]

              (c/su
                (c/exec* "killall -q -9 'beam.smp' 'epmd' || true")
                (try (c/exec* "erl -noshell -eval \"\\$2 /= hd(erlang:system_info(otp_release)) andalso halt(2).\" -run init stop")
                     (catch Exception e
                       (info "Erlang not detected, installing it...")
                       (info "downloading esl dpkg")
                       (let [deb_file (cu/wget! "https://packages.erlang-solutions.com/erlang-solutions_1.0_all.deb")]
                         (c/exec :dpkg :-i deb_file))
                       (info "apt-update")
                       (debian/update!)
                       (info "Installing esl-erlang")
                       (debian/install [:socat :esl-erlang]
                                       )))

                (info "Downloading RabbitMQ " uri)
                (cu/install-archive! uri "/tmp/rabbitmq-server")

                ; Update config
                (c/exec :echo (-> "rabbitmq/rabbitmq.conf"
                                  io/resource
                                  slurp)
                        :> "/tmp/rabbitmq-server/etc/rabbitmq/rabbitmq.conf")
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
                      (info "Join cluster " (str "rabbit@" p))
                      (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl" "join_cluster" (str "rabbit@" p))    
                      (info "Starting app")
                      (c/exec* "/tmp/rabbitmq-server/sbin/rabbitmqctl start_app")
                      (info "App started")
                    )
                  )
                )
                ))))



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
  "Given a channel and an operation, dequeues a value and returns the
  corresponding operation."
  [ch op]
  ; Rabbit+Langohr's auto-ack dynamics mean that even if we issue a dequeue req
  ; then crash, the message should be re-delivered and we can count this as a
  ; failure.
  (timeout 5000 (assoc op :type :fail :value :timeout)
           (let [result (lb/get ch queue false)]
             (if (= nil result)
               (assoc op :type :fail :value :exhausted)
               (let [[meta payload] result]
                 (lb/ack ch (:delivery-tag meta))
                 (assoc op :type :ok :value (codec/decode payload)))))))

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
  [conn]
  client/Client
  (open! [client test node]
    (info "open! called for " node)
    (try
      (assoc client :conn (rmq/connect {:host (name node)
                                      :automatically-recover false}))
    (catch Exception _ client))
    )
  (setup! [client test]
    (with-ch [ch conn]
      ; Initialize queue
      (do
        (lq/declare ch queue
                    {:durable     true
                     :arguments   {"x-queue-type" "quorum"}
                     :auto-delete false
                     :exclusive   false})
        ; give it all a little bit of time before issuing the first command
        (Thread/sleep 1000)
        (lq/purge ch queue)))
    client)

  (teardown! [_ test])
  ; there is nothing to tear down
  (close! [_ test]
      (meh (rmq/close conn)))

  (invoke! [client test op]
    (try 
      (with-ch [ch conn]
        (case (:f op)
          :enqueue (do
                    (lco/select ch) ; Use confirmation tracking

                    ; Empty string is the default exhange
                    (lb/publish ch "" queue
                                (codec/encode (:value op))
                                {:content-type  "application/edn"
                                  :mandatory     true
                                  :persistent    true})

                    ; Block until message acknowledged or crash
                    (try
                        (if (lco/wait-for-confirms ch 5000)
                          (assoc op :type :ok)
                          (assoc op :type :fail))
                      (catch java.util.concurrent.TimeoutException _ (assoc op :type :info :error :timeout)))
                      )

          :dequeue (dequeue! ch op)

          :drain (loop [values []]
            (let [v (dequeue! ch op)]
            (if (= (:type v) :ok)
              (recur (conj values (:value v)))
              (assoc op :type :ok, :value values))))))
         (catch java.util.concurrent.TimeoutException _ 
          (info "channel operation timed out")
          (assoc op :type :info :error :timeout)))   
            ))

(defn queue-client [] (QueueClient. nil))

(defn rabbit-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (info :opts opts)
  (merge tests/noop-test
         {:name       "rabbitmq-simple-partition"
          :os         debian/os
          :db         (db)
          :client     (queue-client)
          :nemesis    (nemesis/partition-random-halves)
          :checker    (checker/total-queue)
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
         opts))

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
   ])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn rabbit-test, :opt-spec cli-opts})
            args))