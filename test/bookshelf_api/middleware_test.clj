(ns bookshelf-api.middleware-test
  (:require [bookshelf-api.lib.middleware :as middleware]
            [clojure.test :refer :all]))

(def ^:dynamic *request-context* nil)

(deftest timeout-preserves-handler-response-and-bindings
  ;; In particular, run this on Java 27: reflective submit can select Runnable
  ;; instead of Callable and discard the response even though the handler ran.
  (let [executor (middleware/executor 1)
        observed (promise)
        response {:status 201 :headers {"Location" "/books/42"}
                  :body {:id 42}}
        handler ((:wrap (middleware/request-timeout executor 2000))
                 (fn [request]
                   (deliver observed [(:uri request) *request-context*])
                   response))]
    (try
      (binding [*request-context* :regression]
        (is (= response (handler {:uri "/books"}))
            (str "Handler response must survive submit on Java "
                 (System/getProperty "java.version"))))
      (is (= ["/books" :regression] (deref observed 2000 ::timeout))
          "The executor must preserve the caller's dynamic bindings")
      (finally (.shutdownNow executor)))))
