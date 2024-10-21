(defproject jepsen.rabbitmq "0.1.0"
  :description "RabbitMQ tests for Jepsen"
  :url "https://github.com/aphyr/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.rabbitmq
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :jvm-opts ["-Dcom.sun.management.jmxremote"]
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [jepsen "0.3.7"]
                 [com.rabbitmq/amqp-client "5.22.0"
                  :exclusions [org.slf4j/slf4j-api]]
                 ]
  :exclusions [org.slf4j/log4j-over-slf4j
               log4j/log4j]
)
