(ns bookshelf-api.system-test
  (:require [bookshelf-api.config-test :refer [defaults]]
            [bookshelf-api.db.migrations :as migrations]
            [bookshelf-api.db.pool :as pool]
            [bookshelf-api.lib.events :as events]
            [bookshelf-api.lib.system :as system]
            [clojure.test :refer :all]
            [ring.adapter.jetty :as jetty]))

(deftest only-migration-role-applies-migrations
  (let [calls (atom 0)]
    (with-redefs [pool/open! (constantly ::db)
                  migrations/migrate! (fn [_] (swap! calls inc))
                  jetty/run-jetty (fn [& _] ::server)
                  events/start-worker! (fn [& _] ::worker)]
      (let [api (system/start! defaults)]
        (try (is (= ::server (:server api))) (is (zero? @calls))
             (finally (.shutdownNow (:executor api)))))
      (is (= ::worker (:worker (system/start! (assoc defaults :app-role "worker")))))
      (is (zero? @calls))
      (system/start! (assoc defaults :app-role "migrate"))
      (is (= 1 @calls)))))
