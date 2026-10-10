(ns bookshelf-api.features.authors.schemas
  (:require [bookshelf-api.features.books.schemas :refer [Id]]))

(def Author [:map [:id Id] [:name :string]])
(def AuthorCount [:map [:author-id Id] [:name :string] [:book-count [:int {:min 0}]]])
