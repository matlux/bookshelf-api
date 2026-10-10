(ns bookshelf-api.http-test
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.lib.http :as http]
            [clojure.test :refer :all]
            [jsonista.core :as json]
            [ring.mock.request :as mock])
  (:import [java.io ByteArrayInputStream]
           [java.sql SQLException]))

(defn body [response]
  (let [b (:body response)]
    (when b
      (json/read-value (if (bytes? b) (ByteArrayInputStream. b) b)
                       json/keyword-keys-object-mapper))))

(def input {:title "A Wizard of Earthsea" :author-id 1 :published-year 1968})
(def book (assoc input :id 7))
(def app (http/app ::db (constantly nil)))

(deftest health-and-authors
  (with-redefs [q/healthy? (constantly true)
                q/list-authors (constantly [{:id 1 :name "Ursula K. Le Guin"}])]
    (let [response (app (mock/request :get "/api/v1/health"))]
      (is (= 200 (:status response)))
      (is (= {:status "ok" :database true} (body response))))
    (is (= [{:id 1 :name "Ursula K. Le Guin"}]
           (body (app (mock/request :get "/api/v1/authors"))))))
  (with-redefs [q/healthy? (fn [_] (throw (SQLException. "offline")))]
    (is (= 503 (:status (app (mock/request :get "/api/v1/health")))))))

(deftest create-and-path-coercion
  (let [notifications (atom 0)
        handler (http/app ::db #(swap! notifications inc))]
    (with-redefs [q/create-book! (fn [db value]
                                 (is (= ::db db)) (is (= input value)) book)
                  q/get-book (fn [_ id] (is (= 7 id)) book)]
      (let [response (handler (-> (mock/request :post "/api/v1/books")
                                  (mock/json-body input)))]
        (is (= 201 (:status response)))
        (is (= "/api/v1/books/7" (get-in response [:headers "Location"])))
        (is (= book (body response))))
      (is (= book (body (handler (mock/request :get "/api/v1/books/7")))))
      (is (= 1 @notifications)))))

(deftest rejects-invalid-requests-before-db
  (with-redefs [q/create-book! (fn [& _] (throw (AssertionError. "DB must not be called")))]
    (doseq [value [(dissoc input :title)
                   (assoc input :title "   ")
                   (assoc input :author-id -1)
                   (assoc input :published-year 10000)
                   (assoc input :unexpected "field")]]
      (is (= 400 (:status (app (-> (mock/request :post "/api/v1/books")
                                   (mock/json-body value)))))))
    (is (= 400 (:status (app (-> (mock/request :post "/api/v1/books")
                                 (mock/content-type "application/json")
                                 (mock/body "{bad-json")))))))
  (doseq [id ["nope" "0" "-1" "9223372036854775808"]]
    (is (= 400 (:status (app (mock/request :get (str "/api/v1/books/" id))))))))

(deftest update-delete-and-missing-books
  (let [notifications (atom 0)
        handler (http/app ::db #(swap! notifications inc))]
    (with-redefs [q/update-book! (fn [_ id value]
                                 (is (= 7 id)) (assoc value :id id))
                  q/delete-book! (fn [_ id] {:id id})]
      (is (= 200 (:status (handler (-> (mock/request :put "/api/v1/books/7")
                                       (mock/json-body input))))))
      (let [response (handler (mock/request :delete "/api/v1/books/7"))]
        (is (= 204 (:status response)))
        (is (nil? (:body response))))
      (is (= 2 @notifications)))
    (with-redefs [q/get-book (constantly nil)
                  q/update-book! (constantly nil)
                  q/delete-book! (constantly nil)]
      (doseq [method [:get :put :delete]]
        (let [request (mock/request method "/api/v1/books/999")
              response (handler (if (= :put method) (mock/json-body request input) request))]
          (is (= 404 (:status response)))
          (is (= {:error "Book not found"} (body response)))))
      (is (= 2 @notifications)))))

(deftest database-errors-and-response-validation
  (with-redefs [q/create-book! (fn [& _] (throw (SQLException. "private detail" "23503")))]
    (let [response (app (-> (mock/request :post "/api/v1/books") (mock/json-body input)))]
      (is (= 422 (:status response)))
      (is (= {:error "Author does not exist"} (body response)))))
  (with-redefs [q/list-books (constantly [{:id "wrong"}])]
    (is (= 500 (:status (app (mock/request :get "/api/v1/books")))))))

(deftest documentation-and-fallbacks
  (let [response (app (mock/request :get "/swagger.json"))
        document (body response)]
    (is (= 200 (:status response)))
    (is (= "2.0" (:swagger document)))
    (is (get-in document [:paths (keyword "/api/v1/books") :post]))
    (is (get-in document [:paths (keyword "/api/v1/authors/book-counts") :get])))
  (is (= 200 (:status (app (mock/request :get "/swagger-ui/index.html")))))
  (is (= 404 (:status (app (mock/request :get "/missing")))))
  (is (= 405 (:status (app (mock/request :patch "/api/v1/books"))))))
