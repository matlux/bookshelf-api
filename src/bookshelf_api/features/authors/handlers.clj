(ns bookshelf-api.features.authors.handlers
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.features.authors.schemas :as s]))

(defn routes [db]
  [["/authors"
    {:get {:summary "List seeded authors" :tags #{"authors"}
           :responses {200 {:body [:vector s/Author]}}
           :handler (fn [_] {:status 200 :body (q/list-authors db)})}}]
   ["/authors/book-counts"
    {:get {:summary "Read the eventually consistent books-per-author rollup"
           :tags #{"authors"}
           :responses {200 {:body [:vector s/AuthorCount]}}
           :handler (fn [_] {:status 200 :body (q/author-counts db)})}}]])
