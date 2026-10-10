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
