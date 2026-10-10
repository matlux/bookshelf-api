(ns bookshelf-api.features.books.handlers
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.features.books.schemas :as s]))

(def not-found {:status 404 :body {:error "Book not found"}})

(defn routes [db notify!]
  [["/books"
    {:get {:summary "List books (default limit 20; maximum 100)" :tags #{"books"}
           :parameters {:query s/BookQuery}
           :responses {200 {:body [:vector s/Book]}}
           :handler (fn [request]
                      {:status 200 :body (q/list-books db (get-in request [:parameters :query]))})}
     :post {:summary "Create a book" :tags #{"books"}
            :parameters {:body s/BookInput}
            :responses {201 {:body s/Book} 422 {:body s/ErrorBody}}
            :handler (fn [request]
                       (let [book (q/create-book! db (get-in request [:parameters :body]))]
                         (notify!)
                         {:status 201
                          :headers {"Location" (str "/api/v1/books/" (:id book))}
                          :body book}))}}]
   ["/books/:id"
    {:parameters {:path s/Path} :tags #{"books"}
     :get {:summary "Get a book"
           :responses {200 {:body s/Book} 404 {:body s/ErrorBody}}
           :handler (fn [request]
                      (if-let [book (q/get-book db (get-in request [:parameters :path :id]))]
                        {:status 200 :body book}
                        not-found))}
     :put {:summary "Replace a book (all fields required)"
           :parameters {:body s/BookInput}
           :responses {200 {:body s/Book} 404 {:body s/ErrorBody} 422 {:body s/ErrorBody}}
           :handler (fn [request]
                      (if-let [book (q/update-book! db
                                                  (get-in request [:parameters :path :id])
                                                  (get-in request [:parameters :body]))]
                        (do (notify!) {:status 200 :body book})
                        not-found))}
     :delete {:summary "Delete a book"
              :responses {204 {:description "Deleted"} 404 {:body s/ErrorBody}}
              :handler (fn [request]
                         (if (q/delete-book! db (get-in request [:parameters :path :id]))
                           (do (notify!) {:status 204 :body nil})
                           not-found))}}]])
