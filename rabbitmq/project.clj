(defproject jepsen.rabbitmq "0.1.0"
  :description "RabbitMQ tests for Jepsen"
  :url "https://github.com/aphyr/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.rabbitmq
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :javac-options     ["-target" "1.8" "-source" "1.8"]
  :jvm-opts ["-Dcom.sun.management.jmxremote"]
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.1.19"]
                 [com.rabbitmq/amqp-client "5.14.0" ]])
