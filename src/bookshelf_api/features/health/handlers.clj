(ns bookshelf-api.features.health.handlers
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.features.health.schemas :as s]))

(defn routes [db]
  [["/health"
    {:get {:summary "API and PostgreSQL readiness" :tags #{"health"}
           :responses {200 {:body s/Health} 503 {:body s/Health}}
           :handler (fn [_]
                      (let [ok? (try (boolean (q/healthy? db)) (catch Exception _ false))]
                        {:status (if ok? 200 503)
                         :body {:status (if ok? "ok" "unavailable") :database ok?}}))}}]])
