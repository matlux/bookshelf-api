(ns bookshelf-api.features.authors.schemas
  (:require [bookshelf-api.features.books.schemas :refer [Id]]))

(def AuthorInput [:map {:closed true}
                  [:name [:and [:string {:min 1 :max 300}] [:re #".*\S.*"]]]])
(def Author (conj AuthorInput [:id Id]))
(def AuthorCount [:map [:author-id Id] [:name :string] [:book-count [:int {:min 0}]]
                  [:refreshed-at [:maybe :string]]])
