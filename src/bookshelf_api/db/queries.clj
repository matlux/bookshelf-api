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

(defn get-author [db id]
  (one! db {:select [:id :name] :from [:authors] :where [:= :id id]}))

(defn create-author! [db author]
  (one! db {:insert-into :authors :values [author] :returning [:id :name]}))

(defn update-author! [db id author]
  (one! db {:update :authors :set author :where [:= :id id] :returning [:id :name]}))

(defn delete-author! [db id]
  (jdbc/with-transaction [tx db]
    ;; Share the rollup writer lock so a refresh cannot insert a count for an
    ;; author being deleted. Only books should prevent author deletion.
    (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(6812402)"])
    (one! tx {:delete-from :authors :where [:= :id id] :returning [:id]})))

(defn list-books
  ([db] (list-books db {}))
  ([db {:keys [limit offset author-id title sort order]
        :or {limit 20 offset 0 sort "id" order "asc"}}]
   ;; Column and direction are selected from an allowlist, never raw query text.
   (let [column ({"id" :id "title" :title "published-year" :published-year} sort)
         direction ({"asc" :asc "desc" :desc} order)
         predicates (cond-> []
                      author-id (conj [:= :author-id author-id])
                      title (conj [:> [:strpos [:lower :title] [:lower title]] 0]))]
     (when-not (and column direction)
       (throw (ex-info "Invalid book sort" {})))
     (many! db (cond-> {:select [:*] :from [:books] :limit limit :offset offset
                       :order-by (cond-> [[column direction]]
                                   (not= column :id) (conj [:id :asc]))}
                (seq predicates) (assoc :where (into [:and] predicates)))))))

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
               [[:author-book-counts [:author-id :book-count :refreshed-at]]
                {:select [:a.id [[:count :b.id] :book-count] [[:statement_timestamp] :refreshed-at]]
                 :from [[:authors :a]]
                 :left-join [[:books :b] [:= :a.id :b.author-id]]
                 :group-by [:a.id]}]})))

(defn author-counts [db]
  (many! db {:select [:c.author-id :a.name :c.book-count
                       [[:to_char [:timezone "UTC" :c.refreshed-at] "YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\""] :refreshed-at]]
             :from [[:author-book-counts :c]]
             :join [[:authors :a] [:= :a.id :c.author-id]]
             :order-by [:c.author-id]}))
