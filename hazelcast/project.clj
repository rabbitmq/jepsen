(defproject jepsen.hazelcast "0.1.0-SNAPSHOT"
  :description "Jepsen tests for hazelcast"
  :url "https://jepsen.io/"
  :license {:name "Eclipse Public License"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.6"]
                 [com.hazelcast/hazelcast-client "3.8"]]
  :main jepsen.hazelcast)
