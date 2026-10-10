(ns bookshelf-api.lib.system
  (:require [bookshelf-api.db.migrations :as migrations]
            [bookshelf-api.db.pool :as pool]
            [bookshelf-api.lib.events :as events]
            [bookshelf-api.lib.http :as http]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty])
  (:import [com.zaxxer.hikari HikariDataSource]
           [org.eclipse.jetty.server Server]))

(defn stop! [{:keys [server worker datasource]}]
  (try
    (when server (.stop ^Server server))
    (finally
      (try
        (when worker ((:stop! worker)))
        (finally
          (when datasource (.close ^HikariDataSource datasource))))))
  (log/info "System stopped"))

(defn start! [{:keys [app-role http-host http-port] :as config}]
  (let [datasource (pool/open! config)]
    (try
      (migrations/migrate! datasource)
      (merge {:datasource datasource}
             (case app-role
               "api" {:server (jetty/run-jetty
                               (http/app datasource (events/publisher config))
                               {:host http-host :port http-port :join? false})}
               "worker" {:worker (events/start-worker! datasource config)}
               "migrate" {}))
      (catch Throwable error
        (.close ^HikariDataSource datasource)
        (throw error)))))
