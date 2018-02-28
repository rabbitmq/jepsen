(ns jepsen.ra
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io       :as io]
            [clojure.string        :as string]
            [jepsen.core           :as core]
            [jepsen.cli            :as cli]
            [jepsen.tests          :as tests]
            [jepsen.util           :refer [meh timeout log-op]]
            [jepsen.core           :as core]
            [jepsen.control        :as c]
            [jepsen.control.util   :as cu]
            [jepsen.client         :as client]
            [jepsen.db             :as db]
            [jepsen.generator      :as gen]
            [knossos.model         :as model]
            [jepsen.checker        :as checker]
            [jepsen.nemesis        :as nemesis]
            [jepsen.os.debian      :as debian]
            [knossos.core          :as knossos]
            [knossos.op            :as op]))

(defn make-nodes
  [nodes]
  (str  "["
        (string/join  ","
                      (map (fn [node] (str "{ra_test,ra_test@" node "}"))
                           nodes))
        "]"))

(defn make-nodename
  [node]
  (str "ra_test@" node))

(defn setup-ra
  [node version]
  (info node "installing ra" version)
  (c/su
    (debian/install [:build-essential
                     :git
                     :make
                     :wget
                     :p7zip-full])
    (let [deb_file (cu/wget! "https://packages.erlang-solutions.com/erlang-solutions_1.0_all.deb")]
      (c/exec :dpkg :-i deb_file))
    (debian/update!)
    (debian/install [:esl-erlang])
    (c/cd "/opt"
      (if (cu/exists? "ra")
          (c/cd "ra"
            (c/exec :git :fetch)
            (c/exec :git :reset :--hard (str "origin/" version)))
          (c/exec :git :clone :--branch version "https://github.com/rabbitmq/ra.git"))
      (c/cd "ra"
        (c/exec :make :distclean)
        (c/exec :make)
        (c/exec :mkdir :-p "deps/elvis_mk/ebin")
        (c/exec :make :escript)
        (info node "Clean up data dir")
        (c/exec :rm :-rf "/tmp/ra_test*")
        (info node "Starting ra daemon")
        (cu/start-daemon!
          {:logfile "/tmp/ra.log"
           :pidfile "/tmp/ra.pid"
           :chdir   "/opt/ra"
          }
          "./ra_fifo_cli"
          :start_erlang
          :--node     (make-nodename node)
          :--data-dir "/tmp")
        ))))


(defn db
  "Ra queue."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (setup-ra node version)
      (core/synchronize test)
      (when (= node (first (:nodes test)))
            (c/on "n6"
              (setup-ra "n6" version)
              (c/cd "/opt/ra"
                (c/exec "./ra_fifo_cli" :start_ra_cluster
                                        :--nodes (make-nodes (:nodes test))))
              )
            ))

    (teardown! [_ test node]
      (info node "tearing down ra")
      (cu/stop-daemon! "/tmp/ra.pid"))))


(defn dequeue!
  [this test op]
  (c/on "n6"
    ; (info "Test: " test)
    ; (info "OP: " op)
    (c/cd "/opt/ra"
      (info "Run dequeue " op)
      (let [message
            (c/exec "./ra_fifo_cli" :dequeue
                    :--nodes (make-nodes (:nodes test))
                    :| :grep "Got message:" :-A "1"
                    :| :grep :-v "Got message"
                    :|| :echo "empty")]
        (if (= message "empty")
          (assoc op :type :fail :value :exhausted)
          (do
            (info "Got message " message)
            (assoc op :type :ok :value (Integer/parseInt message)))
        )))))

(defrecord RaClient [conn]
  client/Client

  (open! [this test node]
    (assoc this :conn node))

  (setup! [this test]
    this)

  (teardown! [this test]
    this)

  (close! [this test]
    this)

  (invoke! [this test op]
    (case (:f op)
      :enqueue  (c/on "n6"
                  (c/cd "/opt/ra"
                    (try
                      (do
                        (c/exec "./ra_fifo_cli" :enqueue
                              :--message (:value op)
                              :--nodes (make-nodes (:nodes test)))
                        (info "Enqueued " op)
                        (assoc op :type :ok))
                      (catch Exception e
                        (do
                          (info "Enqueue failed " op)
                          (assoc op :type :fail))))))

      :dequeue (dequeue! this test op)

      :drain
             ; Note that this does more dequeues than strictly necessary
             ; owing to lazy sequence chunking.
             (let [result (->> (repeat op)                  ; Explode drain into
                          (map #(assoc % :f :dequeue)) ; infinite dequeues, then
                          (map (partial dequeue! this test))  ; dequeue something
                          (take-while op/ok?)  ; as long as stuff arrives,
                          ; (interleave (repeat op))     ; interleave with invokes
                          ; (drop 1)                     ; except the initial one
                          (map (fn [completion]
                                 (info "Completion " completion)
                                 (log-op completion)
                                 (core/conj-op! test completion)
                                 (:value completion)))
                          )]
                (assoc op :type :ok :value result)))))

(defn ra-test
  "FIXME: ra test"
  [opts]
  (merge tests/noop-test
         opts
         {:name "ra queue"
          :os   debian/os
          :db   (db "ra-fifo-cli")
          :client (RaClient. nil)
          :model      (model/unordered-queue)
          :checker    (checker/compose
                        {:queue       (checker/queue)
                         :total-queue (checker/total-queue)})
          :generator  (gen/phases
                         (->> (gen/queue)
                              (gen/delay 1/10)
                              (gen/nemesis
                                (gen/seq
                                  (cycle [(gen/sleep 60)
                                          {:type :info :f :start}
                                          (gen/sleep 60)
                                          {:type :info :f :stop}])))
                              (gen/time-limit 360))
                         (gen/nemesis
                           (gen/once {:type :info, :f :stop}))
                         (gen/log "waiting for recovery")
                         (gen/sleep 60)
                         (gen/clients
                           (gen/each
                             (gen/once {:type :invoke
                                        :f    :drain}))))
          }))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn ra-test})
            args))
