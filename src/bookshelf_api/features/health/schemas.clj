(ns bookshelf-api.features.health.schemas)

(def Health [:map [:status [:enum "ok" "unavailable"]] [:database :boolean]])
