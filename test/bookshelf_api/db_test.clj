(ns bookshelf-api.db-test
  (:require [bookshelf-api.db.migrations :as migrations]
            [bookshelf-api.db.pool :as pool]
            [bookshelf-api.db.queries :as q]
            [bookshelf-api.lib.config :as config]
            [bookshelf-api.lib.events :as events]
            [bookshelf-api.redis-proxy :as redis-proxy]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as ragtime]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]
           [java.util UUID]))

(def ^:dynamic *db*)
(def ^:dynamic *config*)

(defn isolated-schema [run-test]
  (let [settings (config/load-config)
        url (or (System/getenv "TEST_DATABASE_URL") (:database-url settings))
        schema (str "test_" (str/replace (str (UUID/randomUUID)) "-" ""))
        settings (assoc settings :database-url url)]
    (with-open [admin (pool/open! settings)]
      ;; The identifier is generated here, never supplied by a caller.
      (jdbc/execute! admin [(str "CREATE SCHEMA " schema)])
      (try
        (with-open [db (pool/open! (assoc settings :database-url
                                        (str url (if (str/includes? url "?") "&" "?")
                                             "currentSchema=" schema)))]
          (migrations/migrate! db)
          (binding [*db* db *config* settings] (run-test)))
        (finally (jdbc/execute! admin [(str "DROP SCHEMA " schema " CASCADE")]))))))

(use-fixtures :each isolated-schema)

(def sample {:title "The Dispossessed" :author-id 1 :published-year 1974})

(deftest ^:integration migrations-and-crud
  (migrations/migrate! *db*)
  (is (= 3 (count (q/list-authors *db*))))
  (is (q/healthy? *db*))
  (is (empty? (q/list-books *db*)))
  (let [created (q/create-book! *db* sample)
        id (:id created)
        updated (assoc sample :title "Title with ' quote; DROP TABLE books; --")]
    (is (pos-int? id))
    (is (= created (q/get-book *db* id)))
    (is (= [created] (q/list-books *db*)))
    (is (= (assoc updated :id id) (q/update-book! *db* id updated)))
    (is (= {:id id} (q/delete-book! *db* id)))
    (is (nil? (q/get-book *db* id)))
    (is (nil? (q/update-book! *db* id sample)))
    (is (nil? (q/delete-book! *db* id)))))

(deftest ^:integration constraints-and-rollups
  (is (thrown? SQLException (q/create-book! *db* (assoc sample :author-id 99999))))
  (is (thrown? SQLException (q/create-book! *db* (assoc sample :title " "))))
  (let [book (q/create-book! *db* sample)]
    (q/refresh-counts! *db*)
    (is (= [1 0 0] (mapv :book-count (q/author-counts *db*))))
    (q/update-book! *db* (:id book) (assoc sample :author-id 2))
    (q/refresh-counts! *db*)
    (is (= [0 1 0] (mapv :book-count (q/author-counts *db*))))
    (q/delete-book! *db* (:id book))
    (q/refresh-counts! *db*)
    (is (= [0 0 0] (mapv :book-count (q/author-counts *db*))))))

(defn eventually [pred]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (cond (pred) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(deftest ^:integration redis-worker-refreshes-and-reconciles
  (let [settings (assoc *config* :redis-enabled true
                        :redis-channel (str "bookshelf:test:" (UUID/randomUUID)))
        publish! (events/publisher settings)
        ;; The initial subscribe must reconcile this previously missed write.
        first-book (q/create-book! *db* sample)
        worker (events/start-worker! *db* settings)]
    (try
      (is (eventually #(= [1 0 0] (mapv :book-count (q/author-counts *db*)))))
      (q/create-book! *db* sample)
      (publish!)
      (is (eventually #(= [2 0 0] (mapv :book-count (q/author-counts *db*)))))
      (q/delete-book! *db* (:id first-book))
      (publish!)
      (is (eventually #(= [1 0 0] (mapv :book-count (q/author-counts *db*)))))
      (finally ((:stop! worker))))))

(deftest ^:integration authors-and-referential-integrity
  (let [author (q/create-author! *db* {:name "New author"})
        id (:id author)
        book (q/create-book! *db* (assoc sample :author-id id))]
    (is (= author (q/get-author *db* id)))
    (is (= {:id id :name "Renamed"} (q/update-author! *db* id {:name "Renamed"})))
    (q/refresh-counts! *db*)
    (try
      (q/delete-author! *db* id)
      (is false "Referenced authors must not be deleted")
      (catch SQLException error (is (= "23503" (.getSQLState error)))))
    (is (some? (q/get-author *db* id)))
    (q/delete-book! *db* (:id book))
    (is (= {:id id} (q/delete-author! *db* id)))
    (is (nil? (q/get-author *db* id)))
    (is (not-any? #(= id (:author-id %)) (q/author-counts *db*)))
    (is (nil? (q/update-author! *db* id {:name "Absent"})))
    (is (nil? (q/delete-author! *db* id)))))

(deftest ^:integration paginated-filtered-sorted-books
  (doseq [i (range 25)]
    (q/create-book! *db* (assoc sample :title (format "Book %02d" i)
                              :author-id (if (even? i) 1 2) :published-year (+ 1980 i))))
  (is (= 20 (count (q/list-books *db*))))
  (is (= ["Book 03" "Book 04"]
         (mapv :title (q/list-books *db* {:limit 2 :offset 3}))))
  (is (= ["Book 24" "Book 22"]
         (mapv :title (q/list-books *db* {:limit 2 :author-id 1
                                        :sort "published-year" :order "desc"}))))
  (is (= ["Book 10" "Book 11" "Book 12"]
         (mapv :title (q/list-books *db* {:limit 3 :title "bOoK 1" :sort "title"}))))
  (is (empty? (q/list-books *db* {:title "%' OR 1=1 --"})))
  (is (empty? (q/list-books *db* {:offset 99})))
  (is (= [1 2 3] (mapv :id (q/list-books *db* {:limit 3 :title "Book" :sort "id"}))))
  (is (thrown? clojure.lang.ExceptionInfo (q/list-books *db* {:sort "id;DROP TABLE books"}))))

(deftest ^:integration rollup-upgrade-and-timestamps
  ;; Exercise the upgrade from the existing 001 schema, preserving its rows.
  (ragtime/rollback {:datastore (ragtime-jdbc/sql-database *db*)
                    :migrations (migrations/load-migrations)} 1)
  (let [book (q/create-book! *db* sample)]
    (migrations/migrate! *db*)
    (is (= book (q/get-book *db* (:id book))))
    (is (every? nil? (map :refreshed-at (q/author-counts *db*))))
    (q/refresh-counts! *db*)
    (let [times (mapv :refreshed-at (q/author-counts *db*))]
      (is (every? #(instance? java.time.Instant (java.time.Instant/parse %)) times))
      (is (= 1 (count (set times)))))))

(deftest ^:integration concurrent-migrations
  (ragtime/rollback {:datastore (ragtime-jdbc/sql-database *db*)
                    :migrations (migrations/load-migrations)} 2)
  (let [db *db*
        start (promise)
        runs (doall (repeatedly 2 #(future @start (migrations/migrate! db))))]
    (deliver start true)
    (doseq [run runs] (is (not= ::timeout (deref run 10000 ::timeout))))
    (is (= 3 (count (q/list-authors db))))
    (is (= 2 (count (jdbc/execute! db ["SELECT id FROM ragtime_migrations"]))))))

(deftest ^:integration worker-reconnects-after-redis-outage
  (let [proxy (redis-proxy/start! (:redis-url *config*))
        settings (assoc *config* :redis-enabled true :redis-url (:url proxy)
                        :redis-channel (str "bookshelf:reconnect:" (UUID/randomUUID)))
        worker (events/start-worker! *db* settings)]
    (try
      (is (eventually #(some? (:refreshed-at (first (q/author-counts *db*))))))
      (let [before (:refreshed-at (first (q/author-counts *db*)))]
        ;; Cut existing sockets AND refuse new connections until restored.
        ((:stop! proxy))
        (q/create-book! *db* sample)
        ((events/publisher settings)) ; failed publication must not throw
        (Thread/sleep 1200) ; allow a reconnect attempt while the proxy is down
        (is (= [0 0 0] (mapv :book-count (q/author-counts *db*))))
        (is (= before (:refreshed-at (first (q/author-counts *db*)))))
        ((:start! proxy))
        ;; No new publish: resubscription must repair the missed write itself.
        (is (eventually #(= [1 0 0] (mapv :book-count (q/author-counts *db*)))))
        (is (not= before (:refreshed-at (first (q/author-counts *db*))))))
      (finally ((:stop! worker)) ((:stop! proxy))))))
