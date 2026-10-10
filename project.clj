(defproject bookshelf-api "0.1.0-SNAPSHOT"
  :description "A local Clojure REST API for learning service wiring"
  :license {:name "MIT License"
            :url "https://opensource.org/license/mit/"}
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [org.clojure/tools.logging "1.3.1"]
                 [ring/ring-core "1.15.5"]
                 [ring/ring-jetty-adapter "1.15.5"]
                 [metosin/reitit-ring "0.11.0"]
                 [metosin/reitit-malli "0.11.0"]
                 [metosin/reitit-swagger "0.11.0"]
                 [metosin/reitit-swagger-ui "0.11.0"]
                 [metosin/reitit-middleware "0.11.0"]
                 [metosin/muuntaja "0.6.12"]
                 [metosin/jsonista "1.0.1"]
                 [metosin/malli "0.20.3"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [com.github.seancorfield/honeysql "2.7.1479"]
                 [com.zaxxer/HikariCP "7.1.0"]
                 [org.postgresql/postgresql "42.7.14"]
                 [dev.weavejester/ragtime "0.12.1"]
                 [cprop "0.1.21"]
                 [ch.qos.logback/logback-classic "1.6.5"]
                 [redis.clients/jedis "8.0.2"]]
  :main bookshelf-api.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[ring/ring-mock "0.6.2"]]}
             :uberjar {:aot :all}}
  :uberjar-name "bookshelf-api.jar"
  :test-selectors {:default (complement :integration)
                   :integration :integration
                   :all (constantly true)})
