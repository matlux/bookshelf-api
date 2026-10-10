(ns bookshelf-api.db.migrations
  (:require [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as ragtime]))

(defn load-migrations []
  (let [resource (or (io/resource "db/migrations")
                     (throw (ex-info "Migration resources are missing" {})))
        ;; resauce's directory loader does not decode spaces in file URLs.
        ;; Use a decoded file URI on the host, and classpath loading in a JAR.
        migrations (vec (if (= "file" (.getProtocol resource))
                          (ragtime-jdbc/load-directory (io/file (.toURI resource)))
                          (ragtime-jdbc/load-resources "db/migrations")))]
    (when (empty? migrations)
      (throw (ex-info "No migrations found" {})))
    migrations))

(defn migrate! [datasource]
  ;; Serialize API/worker startup, and commit schema + migration history together.
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(6812401)"])
    (ragtime/migrate {:datastore (ragtime-jdbc/sql-database
                                 tx {:migrations-table-exists-sql
                                     "SELECT 1 FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'ragtime_migrations'"})
                     :migrations (load-migrations)})))
