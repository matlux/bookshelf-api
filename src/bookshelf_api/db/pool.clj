(ns bookshelf-api.db.pool
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn open! [{:keys [database-url database-user database-password database-pool-size
                     app-role request-timeout-ms]}]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl database-url)
                 (.setUsername database-user)
                 (.setPassword database-password)
                 (.setMaximumPoolSize database-pool-size)
                 ;; Leave time for both probes within the default HTTP deadline.
                 (.setConnectionTimeout 2000)
                 (.setValidationTimeout 1000)
                 (.setPoolName "bookshelf"))]
    ;; Apply to every new physical API connection, including replacements.
    ;; Worker refreshes and migrations have no HTTP deadline.
    (when (= "api" app-role)
      (.setConnectionInitSql config
        (str "SET statement_timeout = " (quot (* 9 request-timeout-ms) 10))))
    (HikariDataSource. config)))
