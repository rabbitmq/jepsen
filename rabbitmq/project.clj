(defproject jepsen.rabbitmq "0.1.0"
  :description "RabbitMQ tests for Jepsen"
  :url "https://github.com/aphyr/jepsen"
  :license {:name "Eclipse Public License"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.rabbitmq
  :jvm-opts ["-Dcom.sun.management.jmxremote"]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [jepsen "0.1.8"]
                 [com.novemberain/langohr "5.0.0" ]])
