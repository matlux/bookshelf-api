(ns bookshelf-api.features.health.handlers
  (:require [bookshelf-api.db.queries :as q]
            [bookshelf-api.features.health.schemas :as s]
            [bookshelf-api.lib.events :as events]))

(defn routes [db config]
  [["/health/live"
    {:get {:summary "Process liveness (no dependency checks)" :tags #{"health"}
           :responses {200 {:body s/Liveness}}
           :handler (fn [_] {:status 200 :body {:status "ok"}})}}]
   ["/health"
    {:get {:summary "PostgreSQL and enabled Redis readiness" :tags #{"health"}
           :responses {200 {:body s/Health} 503 {:body s/Health}}
           :handler (fn [_]
                      (let [db? (try (boolean (q/healthy? db)) (catch Exception _ false))
                            redis (if (:redis-enabled config)
                                    (if (try (events/healthy? config) (catch Exception _ false))
                                      "ok" "unavailable")
                                    "disabled")
                            ok? (and db? (not= "unavailable" redis))]
                        {:status (if ok? 200 503)
                         :body {:status (if ok? "ok" "unavailable")
                                :database db? :redis redis}}))}}]])
