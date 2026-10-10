(ns bookshelf-api.db.queries
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def options {:builder-fn rs/as-unqualified-kebab-maps})

(defn- one! [db query]
  (jdbc/execute-one! db (sql/format query) options))

(defn- many! [db query]
  (jdbc/execute! db (sql/format query) options))

(defn healthy? [db]
  (= 1 (:ok (one! db {:select [[[:inline 1] :ok]]}))))

(defn list-authors [db]
  (many! db {:select [:id :name] :from [:authors] :order-by [:id]}))

(defn list-books [db]
  (many! db {:select [:*] :from [:books] :order-by [:id]}))

(defn get-book [db id]
  (one! db {:select [:*] :from [:books] :where [:= :id id]}))

(defn create-book! [db book]
  (one! db {:insert-into :books :values [book] :returning [:*]}))

(defn update-book! [db id book]
  (one! db {:update :books :set book :where [:= :id id] :returning [:*]}))

(defn delete-book! [db id]
  (one! db {:delete-from :books :where [:= :id id] :returning [:id]}))

(defn refresh-counts! [db]
  (jdbc/with-transaction [tx db]
    ;; One writer at a time; readers see either complete old or complete new data.
    (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(6812402)"])
    (many! tx {:delete-from :author-book-counts})
    (many! tx {:insert-into
               [[:author-book-counts [:author-id :book-count]]
                {:select [:a.id [[:count :b.id] :book-count]]
                 :from [[:authors :a]]
                 :left-join [[:books :b] [:= :a.id :b.author-id]]
                 :group-by [:a.id]}]})))

(defn author-counts [db]
  (many! db {:select [:c.author-id :a.name :c.book-count]
             :from [[:author-book-counts :c]]
             :join [[:authors :a] [:= :a.id :c.author-id]]
             :order-by [:c.author-id]}))
