(ns bookshelf-api.features.health.schemas)

(def Health [:map [:status [:enum "ok" "unavailable"]] [:database :boolean]
             [:redis [:enum "ok" "unavailable" "disabled"]]])
(def Liveness [:map [:status [:= "ok"]]])
