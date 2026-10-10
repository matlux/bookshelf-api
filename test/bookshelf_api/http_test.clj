(ns bookshelf-api.http-test
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.lib.http :as http]
            [bookshelf-api.lib.config :as config]
            [bookshelf-api.lib.events :as events]
            [bookshelf-api.lib.middleware :as middleware]
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
(def settings (assoc (config/load-config) :redis-enabled false))
(def ^:dynamic *executor*)
(def ^:dynamic *app*)
(defn app [request] (*app* request))

(use-fixtures :once
  (fn [run-tests]
    (let [executor (middleware/executor 8)]
      (try
        (binding [*executor* executor
                  *app* (http/app ::db (constantly nil) settings executor)]
          (run-tests))
        (finally (.shutdownNow executor))))))

(deftest health-and-authors
  (with-redefs [q/healthy? (constantly true)
                q/list-authors (constantly [{:id 1 :name "Ursula K. Le Guin"}])]
    (let [response (app (mock/request :get "/api/v1/health"))]
      (is (= 200 (:status response)))
      (is (= {:status "ok" :database true :redis "disabled"} (body response))))
    (is (= [{:id 1 :name "Ursula K. Le Guin"}]
           (body (app (mock/request :get "/api/v1/authors"))))))
  (with-redefs [q/healthy? (fn [_] (throw (SQLException. "offline")))]
    (is (= 503 (:status (app (mock/request :get "/api/v1/health")))))))

(deftest create-and-path-coercion
  (let [notifications (atom 0)
        handler (http/app ::db #(swap! notifications inc) settings *executor*)]
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
        handler (http/app ::db #(swap! notifications inc) settings *executor*)]
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

(deftest readiness-and-liveness
  (let [handler (http/app ::db (constantly nil) (assoc settings :redis-enabled true) *executor*)]
    (with-redefs [q/healthy? (constantly true) events/healthy? (constantly true)]
      (is (= {:status "ok" :database true :redis "ok"}
             (body (handler (mock/request :get "/api/v1/health"))))))
    (with-redefs [q/healthy? (constantly true)
                  events/healthy? (fn [_] (throw (Exception. "offline")))]
      (let [response (handler (mock/request :get "/api/v1/health"))]
        (is (= 503 (:status response)))
        (is (= "unavailable" (:redis (body response))))))
    (with-redefs [q/healthy? (fn [_] (throw (AssertionError. "must not ping DB")))
                  events/healthy? (fn [_] (throw (AssertionError. "must not ping Redis")))]
      (is (= 200 (:status (handler (mock/request :get "/api/v1/health/live"))))))))

(deftest author-crud
  (let [notifications (atom 0)
        handler (http/app ::db #(swap! notifications inc) settings *executor*)
        author {:id 4 :name "New author"}]
    (with-redefs [q/create-author! (fn [_ value] (is (= {:name "New author"} value)) author)
                  q/get-author (fn [_ id] (is (= 4 id)) author)
                  q/update-author! (fn [_ id value] (is (= 4 id)) (assoc value :id id))
                  q/delete-author! (fn [_ id] (is (= 4 id)) {:id id})]
      (let [response (handler (-> (mock/request :post "/api/v1/authors")
                                  (mock/json-body {:name "New author"})))]
        (is (= 201 (:status response)))
        (is (= "/api/v1/authors/4" (get-in response [:headers "Location"])))
        (is (= author (body response))))
      (is (= author (body (handler (mock/request :get "/api/v1/authors/4")))))
      (is (= {:id 4 :name "Updated"}
             (body (handler (-> (mock/request :put "/api/v1/authors/4")
                                (mock/json-body {:name "Updated"}))))))
      (is (= 204 (:status (handler (mock/request :delete "/api/v1/authors/4")))))
      (is (= 3 @notifications)))
    (with-redefs [q/get-author (constantly nil) q/update-author! (constantly nil)
                  q/delete-author! (constantly nil)]
      (doseq [method [:get :put :delete]]
        (is (= 404 (:status (handler (-> (mock/request method "/api/v1/authors/99")
                                        (mock/json-body {:name "Absent"})))))))
      (is (= 3 @notifications)))
    (doseq [value [{} {:name " "} {:name "ok" :id 1}]]
      (is (= 400 (:status (handler (-> (mock/request :post "/api/v1/authors")
                                      (mock/json-body value)))))))))

(deftest sql-error-mapping
  (doseq [[state status] [["23505" 409] ["23502" 422] ["23514" 422]
                          ["22001" 422] ["22003" 422] ["08006" 500]]]
    (with-redefs [q/create-author! (fn [& _] (throw (SQLException. "private SQL detail" state)))]
      (let [response (app (-> (mock/request :post "/api/v1/authors")
                              (mock/json-body {:name "Name"})))]
        (is (= status (:status response)))
        (is (not (.contains (:error (body response)) "private"))))))
  (with-redefs [q/delete-author! (fn [& _] (throw (SQLException. "referenced" "23503")))]
    (is (= 409 (:status (app (mock/request :delete "/api/v1/authors/1")))))))

(deftest book-query-coercion
  (with-redefs [q/list-books (fn [_ query]
                              (is (= {:limit 2 :offset 1 :author-id 3 :title "A"
                                      :sort "published-year" :order "desc"} query)) [])]
    (is (= 200 (:status (app (mock/request :get
                                         "/api/v1/books?limit=2&offset=1&author-id=3&title=A&sort=published-year&order=desc"))))))
  (doseq [query ["limit=0" "limit=101" "offset=-1" "author-id=x"
                 "sort=drop%20table" "order=sideways" "unknown=yes"]]
    (is (= 400 (:status (app (mock/request :get (str "/api/v1/books?" query))))))))

(deftest request-ids-and-cors
  (let [handler (http/app ::db (constantly nil)
                         (assoc settings :cors-allowed-origins ["http://localhost:5173"]) *executor*)
        preflight (-> (mock/request :options "/api/v1/authors")
                      (mock/header "Origin" "http://localhost:5173")
                      (mock/header "Access-Control-Request-Method" "POST")
                      (mock/header "Access-Control-Request-Headers" "Content-Type, X-Request-ID"))]
    (doseq [path ["/api/v1/health/live" "/missing" "/swagger.json"]]
      (let [response (handler (-> (mock/request :get path) (mock/header "X-Request-ID" "test-123")))]
        (is (= "test-123" (get-in response [:headers "X-Request-ID"])))))
    (let [response (handler (-> (mock/request :get "/missing")
                                (mock/header "X-Request-ID" "bad id!")))]
      (is (uuid? (parse-uuid (get-in response [:headers "X-Request-ID"])))))
    (let [response (handler preflight)]
      (is (= 204 (:status response)))
      (is (= "http://localhost:5173" (get-in response [:headers "Access-Control-Allow-Origin"]))))
    (doseq [request [(mock/header preflight "Origin" "https://untrusted.example")
                     (mock/header preflight "Access-Control-Request-Method" "PATCH")
                     (mock/header preflight "Access-Control-Request-Headers" "Authorization")]]
      (let [response (handler request)]
        (is (= 403 (:status response)))
        (is (nil? (get-in response [:headers "Access-Control-Allow-Origin"])))))
    (let [response (handler (-> (mock/request :get "/missing")
                                (mock/header "Origin" "http://localhost:5173")))]
      (is (= 404 (:status response)))
      (is (= "http://localhost:5173" (get-in response [:headers "Access-Control-Allow-Origin"]))))))

(deftest request-timeout-and-capacity
  (let [executor (middleware/executor 1)
        handler (http/app ::db (constantly nil) (assoc settings :request-timeout-ms 100) executor)
        interrupted (promise)]
    (try
      (with-redefs [q/list-books (fn [& _]
                                  (try (Thread/sleep 10000)
                                       (catch InterruptedException _ (deliver interrupted true))) [])]
        (let [response (handler (mock/request :get "/api/v1/books"))]
          (is (= 504 (:status response)))
          (is (= {:error "Request timed out"} (body response)))
          (is (string? (get-in response [:headers "X-Request-ID"])))
          (is (= true (deref interrupted 1000 false)))))
      (finally (.shutdownNow executor))))
  (let [executor (middleware/executor 1)
        handler (http/app ::db (constantly nil) (assoc settings :request-timeout-ms 2000) executor)
        started (promise) release (promise)]
    (try
      (with-redefs [q/list-books (fn [& _] (deliver started true) @release [])]
        (let [first-request (future (handler (mock/request :get "/api/v1/books")))]
          (try
            (is (= true (deref started 1000 false)))
            (is (= 503 (:status (handler (mock/request :get "/api/v1/books")))))
            (finally (deliver release true)))
          (is (= 200 (:status (deref first-request 2000 nil))))))
      (finally (deliver release true) (.shutdownNow executor)))))
