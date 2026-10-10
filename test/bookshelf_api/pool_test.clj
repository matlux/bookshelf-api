(ns bookshelf-api.pool-test
  (:require [bookshelf-api.db.pool :as pool]
            [bookshelf-api.db.queries :as q]
            [bookshelf-api.lib.config :as config]
            [bookshelf-api.lib.http :as http]
            [bookshelf-api.lib.middleware :as middleware]
            [clojure.test :refer :all]
            [jsonista.core :as json]
            [next.jdbc :as jdbc]
            [ring.mock.request :as mock])
  (:import [java.io ByteArrayInputStream]
           [java.sql SQLException]))

(defn settings [role]
  (let [config (config/load-config)]
    (assoc config :app-role role :request-timeout-ms 1000 :database-pool-size 2
           :database-url (or (System/getenv "TEST_DATABASE_URL") (:database-url config)))))

(defn statement-timeout-ms [connection]
  (:timeout_ms (jdbc/execute-one! connection
                ["SELECT setting::int AS timeout_ms FROM pg_settings WHERE name = 'statement_timeout'"])))

(deftest ^:integration api-pool-limits-statements-and-recovers
  (with-open [db (pool/open! (settings "api"))]
    ;; Hold both connections to verify initialization beyond the first one.
    (with-open [first-connection (jdbc/get-connection db)
                second-connection (jdbc/get-connection db)]
      (is (= 900 (statement-timeout-ms first-connection)))
      (is (= 900 (statement-timeout-ms second-connection)))
      (try
        (jdbc/execute! first-connection ["SELECT pg_sleep(3)"])
        (is false "PostgreSQL must cancel the slow statement")
        (catch SQLException error
          (is (= "57014" (.getSQLState error)))))
      (is (= 1 (:ok (jdbc/execute-one! first-connection ["SELECT 1 AS ok"])))))
    ;; Replacement physical connections must inherit the timeout too.
    (.softEvictConnections (.getHikariPoolMXBean db))
    (with-open [replacement (jdbc/get-connection db)]
      (is (= 900 (statement-timeout-ms replacement))))))

(deftest ^:integration background-roles-do-not-inherit-http-deadline
  (doseq [role ["worker" "migrate"]]
    (with-open [db (pool/open! (settings role))]
      (is (= 0 (statement-timeout-ms db)) (str role " retains the local server default")))))

(deftest ^:integration database-timeout-returns-504-and-releases-capacity
  (let [config (assoc (settings "api") :redis-enabled false)
        executor (middleware/executor 1)
        sql-state (promise)]
    (try
      (with-open [db (pool/open! config)]
        (let [handler (http/app db (constantly nil) config executor)]
          (with-redefs [q/list-books (fn [db _]
                                      (try
                                        (jdbc/execute! db ["SELECT pg_sleep(3)"])
                                        []
                                        (catch SQLException error
                                          (deliver sql-state (.getSQLState error))
                                          (throw error))))]
            (let [response (handler (mock/request :get "/api/v1/books"))
                  body (:body response)]
              (is (= 504 (:status response)))
              (is (= {:error "Database statement timed out or was canceled"}
                     (json/read-value (if (bytes? body) (ByteArrayInputStream. body) body)
                                      json/keyword-keys-object-mapper)))
              (is (string? (get-in response [:headers "X-Request-ID"]))))
            (is (= "57014" (deref sql-state 2000 ::timeout))
                "PostgreSQL cancellation must release the running query"))
          (with-redefs [q/list-books (fn [db _] (jdbc/execute! db ["SELECT 1"]) [])]
            (is (= 200 (:status (handler (mock/request :get "/api/v1/books")))))
            (is (zero? (.getActiveConnections (.getHikariPoolMXBean db)))))))
      (finally (.shutdownNow executor)))))
