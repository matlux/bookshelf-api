(ns bookshelf-api.lib.http
  (:require [bookshelf-api.features.authors.handlers :as authors]
            [bookshelf-api.features.books.handlers :as books]
            [bookshelf-api.features.health.handlers :as health]
            [bookshelf-api.lib.middleware :as middleware]
            [clojure.tools.logging :as log]
            [muuntaja.core :as muuntaja]
            [reitit.coercion.malli :as malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as format]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui])
  (:import [java.sql SQLException]))

(defn- internal-error [error _]
  (log/error error "Request failed")
  {:status 500 :body {:error "Internal server error"}})

(defn- sql-error [^SQLException error request]
  (case (.getSQLState error)
    "23503" (if (= :delete (:request-method request))
              {:status 409 :body {:error "Resource is still referenced"}}
              {:status 422 :body {:error "Author does not exist"}})
    "23505" {:status 409 :body {:error "Resource already exists"}}
    ("23502" "23514" "22001" "22003")
    {:status 422 :body {:error "Data violates a database constraint"}}
    (internal-error error request)))

(defn app [db notify! config executor]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "Bookshelf API" :version "1.0.0"
                              :description "Local books and authors learning service"}}
             :handler (swagger/create-swagger-handler)}}]
     (into ["/api/v1"] (concat (health/routes db config)
                              (books/routes db notify!)
                              (authors/routes db notify!)))]
    {:data {:coercion (malli/create {:strip-extra-keys false})
            :responses {409 {:body [:map [:error :string]]}
                        422 {:body [:map [:error :string]]}}
            :muuntaja (muuntaja/create
                       (assoc muuntaja/default-options
                              :formats (select-keys (:formats muuntaja/default-options)
                                                    ["application/json"])))
            :middleware [swagger/swagger-feature
                         parameters/parameters-middleware
                         format/format-negotiate-middleware
                         format/format-response-middleware
                         (exception/create-exception-middleware
                          (merge exception/default-handlers
                                 {SQLException sql-error
                                  ::exception/default internal-error}))
                         format/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/swagger-ui"
      :config {:url "/swagger.json" :validatorUrl nil}})
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :headers {"Content-Type" "application/json"}
                              :body "{\"error\":\"Route not found\"}"})
      :method-not-allowed (constantly {:status 405 :headers {"Content-Type" "application/json"}
                                       :body "{\"error\":\"Method not allowed\"}"})}))
   {:middleware [middleware/request-id
                 middleware/access-log
                 (middleware/cors (:cors-allowed-origins config))
                 (middleware/request-timeout executor (:request-timeout-ms config))]}))
