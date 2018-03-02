(ns jepsen.rabbitmq
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io       :as io]
            [jepsen.core           :as core]
            [jepsen.util           :refer [meh timeout log-op]]
            [jepsen.codec          :as codec]
            [jepsen.core           :as core]
            [jepsen.control        :as c]
            [jepsen.control.util   :as cu]
            [jepsen.client         :as client]
            [jepsen.db             :as db]
            [jepsen.generator      :as gen]
            [knossos.core          :as knossos]
            [knossos.op            :as op]
            [langohr.core          :as rmq]
            [langohr.channel       :as lch]
            [langohr.confirm       :as lco]
            [langohr.queue         :as lq]
            [langohr.exchange      :as le]
            [langohr.basic         :as lb]
            [langohr.consumers     :as lcons]
            [jepsen.os.debian      :as debian])
  (:import (com.rabbitmq.client AlreadyClosedException
                                ShutdownSignalException)))

(def db
  (reify db/DB
    (setup! [_ test node]
      (c/cd "/tmp"
            (let [version "3.7.0"
                  file "rabbitmq-server_3.7.0+rc.2.20.g7f36946.dirty-1_all.deb"] ; (str "rabbitmq-server_" version "-1_all.deb")]
              (when-not (cu/file? file)
                (info node "Fetching deb package")
                (c/exec :wget "https://s3-eu-west-1.amazonaws.com/rabbitmq-share/builds/rabbitmq-server_3.7.0%2Brc.2.20.g7f36946.dirty-1_all.deb")); (str "https://dl.bintray.com/rabbitmq/all/rabbitmq-server/" version "/" file)))

              (c/su
                (core/synchronize test)
                ; Install package
                (try (c/exec :dpkg-query :-l :rabbitmq-server)
                     (catch RuntimeException _
                       (info node "Installing esl-erlang")
                       (let [deb_file (cu/wget! "https://packages.erlang-solutions.com/erlang-solutions_1.0_all.deb")]
                         (c/exec :dpkg :-i deb_file))
                       (debian/update!)
                       (debian/install [:esl-erlang
                                        :socat
                                        :adduser
                                        :logrotate])
                       ; (c/exec :apt-get :install :-y :erlang-nox)
                       (info node "Installing rabbitmq")
                       (c/exec :dpkg :-i file)))

                (core/synchronize test)

                (c/exec :service :rabbitmq-server :stop)
                ; Set cookie
                (when-not (= "jepsen-rabbitmq"
                             (c/exec :cat "/var/lib/rabbitmq/.erlang.cookie"))
                  (info node "Setting cookie")
                  (c/exec :echo "jepsen-rabbitmq"
                          :> "/var/lib/rabbitmq/.erlang.cookie"))

                (core/synchronize test)
                ; Update config
                (info node "uploading config")
                (c/exec :echo
                        (-> "rabbitmq/rabbitmq.config" io/resource slurp)
                        :> "/etc/rabbitmq/rabbitmq.config")

                ; Ensure node is running
                (try (c/exec :service :rabbitmq-server :status)
                     (catch RuntimeException _
                     (info "Starting rabbitmq")
                     (c/exec :service :rabbitmq-server :start)))

                ; Prepare for cluster join
                (when-not (= node (first (:nodes test)))
                  (c/exec :rabbitmqctl :stop_app))

                ; Wait for everyone to start up
                (core/synchronize test)

                ; Join
                (let [p (core/primary test)]
                  (when-not (= node p)
                    (info node "joining" p)
                    (c/exec :rabbitmqctl :join_cluster (str "rabbit@" (name p)))
                    (info node "joined" p)
                    (c/exec :rabbitmqctl :start_app)
                    (info node "started")))

                ; Use mirroring
                (core/synchronize test)
                (info node "Enabling mirroring")
                (c/exec :rabbitmqctl :set_policy :ha-maj "jepsen."
                        "{\"ha-mode\": \"exactly\",
                          \"ha-params\": 3,
                          \"ha-sync-mode\": \"automatic\"}")

                (info node "Rabbit ready")))))

    (teardown! [_ test node]
      (c/su
;        (info "Stopping rabbitmq")
;        (meh (c/exec :rabbitmqctl :stop_app))
;        (meh (c/exec :rabbitmqctl :force_reset))
;        (meh (c/exec :service :rabbitmq-server :stop))
        (core/synchronize test)
        (info node "Nuking rabbit")
        (meh (c/exec :killall :-9 "beam.smp" "epmd"))
        (c/exec :rm :-rf "/var/lib/rabbitmq/mnesia/")
        (c/exec :service :rabbitmq-server :stop)
        (info node "Rabbit dead")
        (core/synchronize test)))))

(def queue "jepsen.queue")

(defn dequeue!
  "Given a channel and an operation, dequeues a value and returns the
  corresponding operation."
  [ch op]
  ; Rabbit+Langohr's auto-ack dynamics mean that even if we issue a dequeue req
  ; then crash, the message should be re-delivered and we can count this as a
  ; failure.
  (timeout 5000 (assoc op :type :fail :value :timeout)
           (let [[meta payload] (lb/get ch queue)
                 value          (codec/decode payload)]
             (if (nil? meta)
               (assoc op :type :fail :value :exhausted)
               (assoc op :type :ok :value value)))))

(defmacro with-ch
  "Opens a channel on 'conn for body, binds it to the provided symbol 'ch, and
  ensures the channel is closed after body returns."
  [[ch conn] & body]
  `(let [~ch (lch/open ~conn)]
     (try ~@body
          (finally
            (try (rmq/close ~ch)
                 (catch AlreadyClosedException _#))))))

(defn subscribe!
  "Subscribe and put all received values"
  [promise_box ch]
  ; Make sure messages are received one-by-one, so we can use promise to communicate
  (lb/qos ch 1)
  (let [handler (fn [ch {:keys [delivery-tag]} ^bytes payload]
                  (let [unboxed_promise (deref promise_box)
                        value (codec/decode payload)]
                     (if (realized? unboxed_promise)
                        (do
                          (info "Promise is realized " (deref unboxed_promise) " value is " value " delivery tag is " delivery-tag " channel " ch)
                          (throw (RuntimeException. "Delivery promise should be empty! Check QOS prefetch")))
                        (do
                          (deliver unboxed_promise {:tag delivery-tag, :value value})))))]
    (info "Subscribing")
    (lcons/subscribe
      ch queue
      handler
      {:auto-ack false})))

(defn dequeue_from_promise!
  "Get a message from a promise, which subscribe handler should put values in
  and acknowledge it"
  [promise_box ch op]

  (timeout 5000 (assoc op :type :fail :value :timeout)
    (let [unboxed_promise (deref promise_box)
          {delivery-tag :tag value :value} (deref unboxed_promise)]
      ; Empty the promise in the promise box, so the next delivery be put there
      (swap! promise_box (fn [_] (promise)))
      (lb/ack ch delivery-tag)
      (assoc op :type :ok :value value))))


(defrecord QueueClient [conn promise_box]
  client/Client
  (setup! [_ test node]
    (let [conn (rmq/connect {:host (name node)})]
      (with-ch [ch conn]
        ; Initialize queue
        (lq/declare ch queue
                    {:durable     true
                     :auto-delete false
                     :exclusive   false
                     :x-queue-type "quorum"
                     }))

      ; Return client
      (QueueClient. conn (atom (promise)))))

  (teardown! [_ test]
    ; Purge
    (meh (with-ch [ch conn]
           (lq/purge ch queue)))

    ; Close
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

                   ; Block until message acknowledged
                   (if (lco/wait-for-confirms ch 5000)
                     (assoc op :type :ok)
                     (assoc op :type :fail)))

        :dequeue
          (do
            (swap! promise_box (fn [_] (promise)))

            (let [consumer-tag (subscribe! promise_box ch)
                  result (dequeue_from_promise! promise_box ch op)]
              (lb/cancel ch consumer-tag)
              ; Cleanup delivered values
              (swap! promise_box (fn [_] (promise)))
              result))

        :drain   (do
                   (subscribe! promise_box ch)
                   ; Note that this does more dequeues than strictly necessary
                   ; owing to lazy sequence chunking.
                   (->> (repeat op)                  ; Explode drain into
                        (map #(assoc % :f :dequeue)) ; infinite dequeues, then
                        (map (partial dequeue_from_promise! promise_box ch))  ; dequeue something
                        (take-while op/ok?)  ; as long as stuff arrives,
                        (interleave (repeat op))     ; interleave with invokes
                        (drop 1)                     ; except the initial one
                        (map (fn [completion]
                               (log-op completion)
                               (core/conj-op! test completion)))
                        dorun)
                   (assoc op :type :ok :value :exhausted))))))

(defn queue-client [] (QueueClient. nil nil))

; https://www.rabbitmq.com/blog/2014/02/19/distributed-semaphores-with-rabbitmq/
; enqueued is shared state for whether or not we enqueued the mutex record
; held is independent state to store the currently held message
; (defrecord Semaphore [enqueued? conn ch tag]
;   client/Client
;   (setup! [_ test node]
;     (let [conn (rmq/connect {:host (name node)})]
;       (with-ch [ch conn]
;         (lq/declare ch "jepsen.semaphore"
;                     {:durable true
;                      :auto-delete false
;                      :exclusive false})

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
