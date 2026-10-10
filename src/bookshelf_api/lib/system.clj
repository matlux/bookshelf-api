(ns bookshelf-api.lib.system
  (:require [bookshelf-api.db.migrations :as migrations]
            [bookshelf-api.db.pool :as pool]
            [bookshelf-api.lib.events :as events]
            [bookshelf-api.lib.http :as http]
            [bookshelf-api.lib.middleware :as middleware]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty])
  (:import [com.zaxxer.hikari HikariDataSource]
           [org.eclipse.jetty.server Server]
           [java.util.concurrent ExecutorService]))

(defn stop! [{:keys [server worker datasource executor]}]
  (try
    (when server (.stop ^Server server))
    (finally
      (try
        (when worker ((:stop! worker)))
        (finally
          (when executor (.shutdownNow ^ExecutorService executor))
          (when datasource (.close ^HikariDataSource datasource))))))
  (log/info "System stopped"))

(defn start! [{:keys [app-role http-host http-port] :as config}]
  (let [datasource (pool/open! config)
        executor (when (= "api" app-role) (middleware/executor (:request-threads config)))]
    (try
      (merge {:datasource datasource :executor executor}
             (case app-role
               "api" {:server (jetty/run-jetty
                               (http/app datasource (events/publisher config) config executor)
                               {:host http-host :port http-port :join? false})}
               "worker" {:worker (events/start-worker! datasource config)}
               "migrate" (do (migrations/migrate! datasource) {})))
      (catch Throwable error
        (when executor (.shutdownNow ^ExecutorService executor))
        (.close ^HikariDataSource datasource)
        (throw error)))))
