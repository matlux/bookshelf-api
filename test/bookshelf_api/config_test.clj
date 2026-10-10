(ns bookshelf-api.config-test
  (:require [bookshelf-api.lib.config :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [cprop.core :as cprop]))

(def defaults (edn/read-string (slurp (io/resource "config.edn"))))

(deftest config-fails-fast-without-leaking-values
  (with-redefs [cprop/load-config (constantly defaults)]
    (is (= defaults (config/load-config))))
  (doseq [overrides [{:http-port 0} {:http-port "not-a-number"} {:app-role "typo"}
                     {:database-url "secret-value"} {:request-timeout-ms -1}
                     {:request-threads 0} {:cors-allowed-origins ["*"]}
                     {:app-role "worker" :redis-enabled false}]]
    (with-redefs [cprop/load-config (constantly (merge defaults overrides))]
      (try
        (config/load-config)
        (is false (str "Expected invalid config: " (keys overrides)))
        (catch clojure.lang.ExceptionInfo error
          (is (empty? (ex-data error)))
          (is (not (.contains (.getMessage error) "secret-value"))))))))
