(ns bookshelf-api.lib.config
  (:require [cprop.core :as cprop]
            [malli.core :as m]))

(def Config
  [:map
   [:app-role [:enum "api" "worker" "migrate"]]
   [:http-host :string]
   [:http-port [:int {:min 1 :max 65535}]]
   [:database-url [:re #"^jdbc:postgresql://.+"]]
   [:database-user :string]
   [:database-password :string]
   [:database-pool-size [:int {:min 1 :max 50}]]
   [:redis-enabled :boolean]
   [:redis-url [:re #"^redis://.+"]]
   [:redis-channel [:string {:min 1}]]])

(defn load-config []
  (let [config (cprop/load-config :resource "config.edn")]
    (when-not (m/validate Config config)
      ;; Do not put connection strings or passwords in logs/ex-data.
      (throw (ex-info "Invalid configuration; check environment variables against README" {})))
    (when (and (= "worker" (:app-role config)) (not (:redis-enabled config)))
      (throw (ex-info "APP_ROLE=worker requires REDIS_ENABLED=true" {})))
    config))
