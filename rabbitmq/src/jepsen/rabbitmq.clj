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
            (meh (c/exec :killall :-9 "beam.smp" "epmd"))
            (info "Deleting rabbitmq")
            (meh (c/exec :service :rabbitmq-server :stop))
            (c/exec :rm :-rf "/var/lib/rabbitmq/mnesia")

            (let [uri "https://dl.bintray.com/rabbitmq/all-dev-qq/rabbitmq-server/0.0.0-alpha.98/rabbitmq-server_0.0.0~alpha.98-1_all.deb"
                  file "rabbitmq-server_0.0.0~alpha.98-1_all.deb" ]
              ; (c/exec :rm file)

              (c/su
                (try (c/exec* "erl -noshell -eval \"\\$2 /= hd(erlang:system_info(otp_release)) andalso halt(2).\" -run init stop")
                     (catch Exception e
                       (info "caught " e)
                       (info "downloading esl dpkg")
                       (let [deb_file (cu/wget! "https://packages.erlang-solutions.com/erlang-solutions_1.0_all.deb")]
                         (c/exec :dpkg :-i deb_file))
                       (info "apt-update")
                       (debian/update!)
                       (info "Installing esl-erlang")
                       (debian/install [:socat :esl-erlang]
                                       )))

                (c/exec :rm :-rf "/var/lib/rabbitmq/mnesia")
                (when-not (cu/exists? file)
                  (cu/wget! uri true)
                  ; prepare rabbitmq user and directories
                  (info "ensure user rabbitmq")
                  (cu/ensure-user! "rabbitmq")
                  (c/exec :mkdir :-p "/var/lib/rabbitmq")
                  (c/exec :mkdir :-p "/var/log/rabbitmq")
                  (c/exec :mkdir :-p "/etc/rabbitmq")
                  (c/exec :chmod :a+rwx "/var/lib/rabbitmq/")
                  (c/exec :chmod :a+rwx "/var/log/rabbitmq/")
                  (info "remove policy-rc.d file")
                  (c/exec :rm :-f "/usr/sbin/policy-rc.d")

                  ; Update config
                  (let [p (core/primary test)]
                    (let [r (if-not (= node p) (str "rabbit@" p) "")]
                      (c/exec :echo (-> "rabbitmq/rabbitmq.config"
                                        io/resource
                                        slurp
                                        (str/replace "$RMQ_NODE" r))
                              :> "/etc/rabbitmq/rabbitmq.config")))
                  (info "Installing rabbitmq " file)
                  (c/exec :dpkg :-i :--no-triggers file)
                  ; Set cookie
                  (when-not (= "jepsen-rabbitmq"
                               (c/exec :cat "/home/rabbitmq/.erlang.cookie"))
                    (c/exec :echo "jepsen-rabbitmq"
                            :> "/home/rabbitmq/.erlang.cookie"))
                  )

                ; (c/exec :service :rabbitmq-server :stop)
                ; Ensure node is running
                (info "Starting rabbitmq")
                (let [p (core/primary test)]
                  (if (= node p)
                    (c/exec :service :rabbitmq-server :start)))
                ; wait for the primary to come up
                (core/synchronize test)
                ; start the remaining nodes
                (let [p (core/primary test)]
                  (if-not (= node p)
                    (c/exec :service :rabbitmq-server :start)))

                ; wait for all nodes to be clustered and ready
                (c/exec :rabbitmqctl :await_online_nodes 4)
                (core/synchronize test)

                (info node "Rabbit ready")))))



  (teardown! [_ test node]
             (c/su
               ; there is no real need to clear anything down here as we
               ; reset everything before each run
               (info node "Teardown complete")))
    db/LogFiles
    (log-files [_ test node]
      [
       (str "/var/log/rabbitmq/rabbit@" node ".log")
       (str "/var/log/rabbitmq/log/crash.log")
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
                 (catch AlreadyClosedException _#))))))

(defrecord QueueClient
  [conn]
  client/Client
  (open! [this test node]
    (info "open! called for " node)
    (try
      (assoc this :conn (rmq/connect {:host (name node)
                                      :automatically-recover false}))
    (catch Exception _ this))
    )
  (setup! [this test]
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
    this)

  (teardown! [_ test])
  ; there is nothing to tear down
  (close! [_ test]
      (meh (rmq/close conn)))

  (invoke! [this test op]
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
                   (if (lco/wait-for-confirms ch 5000)
                     (assoc op :type :ok)
                     (assoc op :type :fail)))

        :dequeue (dequeue! ch op)

        :drain   (do
                   (info "doing DRAIN")
                   ; Note that this does more dequeues than strictly necessary
                   ; owing to lazy sequence chunking.
                   (->> (repeat op)                  ; Explode drain into
                        (map #(assoc % :f :dequeue)) ; infinite dequeues, then
                        (map (partial dequeue! ch))  ; dequeue something
                        (take-while op/ok?)          ; as long as stuff arrives,
                        (interleave (repeat op))     ; interleave with invokes
                        (drop 1)                     ; except the initial one
                        (map (fn [completion]
                               (log-op completion)
                               (core/conj-op! test completion)))
                        dorun)
                   (assoc op :type :fail :value :exhausted))))))

(defn queue-client [] (QueueClient. nil))

; https://www.rabbitmq.com/blog/2014/02/19/distributed-semaphores-with-rabbitmq/
; enqueued is shared state for whether or not we enqueued the mutex record
; held is independent state to store the currently held message
; (defrecord Semaphore [enqueued? conn ch tag]
;   client/Client
;   (open! [this test node]
;     (assoc this :conn (rmq/connect {:host (name node)})))
;   (setup! [test node]
;     (let [conn (rmq/connect {:host (name node)})]
;       (with-ch [ch conn]
;         (lq/declare ch "jepsen.semaphore"
;                     :durable true
;                     :auto-delete false
;                     :exclusive false)

;         ; Enqueue a single message
;         (when (compare-and-set! enqueued? false true)
;           (lco/select ch)
;           (lq/purge ch "jepsen.semaphore")
;           (lb/publish ch "" "jepsen.semaphore" (byte-array 0))
;           (when-not (lco/wait-for-confirms ch 5000)
;             (throw (RuntimeException.
;                      "couldn't enqueue initial semaphore message!")))))

;       (Semaphore. enqueued? conn (atom (lch/open conn)) (atom nil))))

;   (teardown! [_ test]
;     ; Purge
;     (meh (timeout 5000 nil
;                   (with-ch [ch conn]
;                     (lq/purge ch "jepsen.semaphore"))))
;     (meh (rmq/close @ch))
;     (meh (rmq/close conn)))
;   (close! [_ test]

;   (invoke! [this test op]
;     (case (:f op)
;       :acquire (locking tag
;                  (if @tag
;                    (assoc op :type :fail :value :already-held)

;                    (timeout 5000 (assoc op :type :fail :value :timeout)
;                       (try
;                         ; Get a message but don't acknowledge it
;                         (let [dtag (-> (lb/get @ch "jepsen.semaphore" false)
;                                        first
;                                        :delivery-tag)]
;                           (if dtag
;                             (do (reset! tag dtag)
;                                 (assoc op :type :ok :value dtag))
;                             (assoc op :type :fail)))

;                         (catch ShutdownSignalException e
;                           (meh (reset! ch (lch/open conn)))
;                           (assoc op :type :fail :value (.getMessage e)))

;                         (catch AlreadyClosedException e
;                           (meh (reset! ch (lch/open conn)))
;                           (assoc op :type :fail :value :channel-closed))))))

;       :release (locking tag
;                  (if-not @tag
;                    (assoc op :type :fail :value :not-held)
;                    (timeout 5000 (assoc op :type :ok :value :timeout)
;                             (let [t @tag]
;                               (reset! tag nil)
;                               (try
;                                 ; We're done now--we try to reject but it
;                                 ; doesn't matter if we succeed or not.
;                                 (lb/reject @ch t true)
;                                 (assoc op :type :ok)

;                                 (catch AlreadyClosedException e
;                                   (meh (reset! ch (lch/open conn)))
;                                   (assoc op :type :ok :value :channel-closed))

;                                 (catch ShutdownSignalException e
;                                   (assoc op
;                                          :type :ok
;                                          :value (.getMessage e)))))))))))

; (defn mutex [] (Semaphore. (atom false) nil nil nil))

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
          :model      (model/unordered-queue)
          :checker    (checker/total-queue)
          ; :checker    (checker/compose
          ;               {:queue       (checker/queue)
          ;                :total-queue (checker/total-queue)})
          :generator  (gen/phases
                        (->> (gen/queue)
                             (gen/delay 1/10)
                             (gen/nemesis
                               (gen/seq
                                 (cycle [(gen/sleep 60)
                                         {:type :info :f :start}
                                         (gen/sleep 60)
                                         {:type :info :f :stop}])))
                             (gen/time-limit 180))
                        (gen/nemesis
                          (gen/once {:type :info, :f :stop}))
                        (gen/log "waiting for recovery")
                        (gen/sleep 60)
                        (gen/clients
                          (gen/each
                            (gen/once {:type :invoke
                                       :f    :drain}))))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn rabbit-test})
                   (cli/serve-cmd))
            args))
