(ns bookshelf-api.db-test
  (:require [bookshelf-api.db.migrations :as migrations]
            [bookshelf-api.db.pool :as pool]
            [bookshelf-api.db.queries :as q]
            [bookshelf-api.lib.config :as config]
            [bookshelf-api.lib.events :as events]
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
