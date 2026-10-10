(ns bookshelf-api.features.books.schemas)

(def Id [:int {:min 1 :max Long/MAX_VALUE}])
(def BookInput
  [:map {:closed true}
   [:title [:and [:string {:min 1 :max 300}] [:re #".*\S.*"]]]
   [:author-id Id]
   [:published-year [:int {:min 1 :max 9999}]]])
(def Book (conj BookInput [:id Id]))
(def ErrorBody [:map [:error :string]])
(def Path [:map [:id Id]])

(def BookQuery
  [:map {:closed true}
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0 :max 2147483647}]]
   [:author-id {:optional true} Id]
   [:title {:optional true} [:string {:min 1 :max 300}]]
   [:sort {:optional true} [:enum "id" "title" "published-year"]]
   [:order {:optional true} [:enum "asc" "desc"]]])
