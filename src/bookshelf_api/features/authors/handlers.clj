(ns bookshelf-api.features.authors.handlers
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.features.authors.schemas :as s]
            [bookshelf-api.features.books.schemas :as books]))

(def not-found {:status 404 :body {:error "Author not found"}})

(defn routes [db notify!]
  [["/authors"
    {:tags #{"authors"}
     :get {:summary "List authors"
           :responses {200 {:body [:vector s/Author]}}
           :handler (fn [_] {:status 200 :body (q/list-authors db)})}
     :post {:summary "Create an author"
            :parameters {:body s/AuthorInput}
            :responses {201 {:body s/Author}}
            :handler (fn [request]
                       (let [author (q/create-author! db (get-in request [:parameters :body]))]
                         (notify!)
                         {:status 201 :body author
                          :headers {"Location" (str "/api/v1/authors/" (:id author))}}))}}]
   ["/authors/book-counts"
    {:conflicting true ; static route takes precedence over /authors/:id
     :get {:summary "Read book counts and their last refresh time"
           :tags #{"authors"}
           :responses {200 {:body [:vector s/AuthorCount]}}
           :handler (fn [_] {:status 200 :body (q/author-counts db)})}}]
   ["/authors/:id"
    {:conflicting true
     :tags #{"authors"} :parameters {:path books/Path}
     :get {:summary "Get an author"
           :responses {200 {:body s/Author} 404 {:body books/ErrorBody}}
           :handler (fn [request]
                      (if-let [author (q/get-author db (get-in request [:parameters :path :id]))]
                        {:status 200 :body author}
                        not-found))}
     :put {:summary "Replace an author's name"
           :parameters {:body s/AuthorInput}
           :responses {200 {:body s/Author} 404 {:body books/ErrorBody}}
           :handler (fn [request]
                      (if-let [author (q/update-author! db
                                                       (get-in request [:parameters :path :id])
                                                       (get-in request [:parameters :body]))]
                        (do (notify!) {:status 200 :body author})
                        not-found))}
     :delete {:summary "Delete an author (409 if books still reference it)"
              :responses {204 {:description "Deleted"} 404 {:body books/ErrorBody}
                          409 {:body books/ErrorBody}}
              :handler (fn [request]
                         (if (q/delete-author! db (get-in request [:parameters :path :id]))
                           (do (notify!) {:status 204 :body nil})
                           not-found))}}]])
