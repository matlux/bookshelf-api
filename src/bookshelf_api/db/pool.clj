(ns bookshelf-api.db.pool
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn open! [{:keys [database-url database-user database-password database-pool-size]}]
  (HikariDataSource.
   (doto (HikariConfig.)
     (.setJdbcUrl database-url)
     (.setUsername database-user)
     (.setPassword database-password)
     (.setMaximumPoolSize database-pool-size)
     ;; Leave time for both dependency probes within the default HTTP deadline.
     (.setConnectionTimeout 2000)
     (.setValidationTimeout 1000)
     (.setPoolName "bookshelf"))))
