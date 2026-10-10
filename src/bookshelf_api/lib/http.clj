(ns bookshelf-api.lib.http
  (:require [bookshelf-api.features.authors.handlers :as authors]
            [bookshelf-api.features.books.handlers :as books]
            [bookshelf-api.features.health.handlers :as health]
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
  (if (= "23503" (.getSQLState error))
    {:status 422 :body {:error "Author does not exist"}}
    (internal-error error request)))

(defn app [db notify!]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "Bookshelf API" :version "1.0.0"
                              :description "Local books and authors learning service"}}
             :handler (swagger/create-swagger-handler)}}]
     (into ["/api/v1"] (concat (health/routes db)
                              (books/routes db notify!)
                              (authors/routes db)))]
    {:data {:coercion (malli/create {:strip-extra-keys false})
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
                                       :body "{\"error\":\"Method not allowed\"}"})}))))
