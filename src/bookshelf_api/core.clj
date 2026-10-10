(ns bookshelf-api.core
  (:gen-class)
  (:require [bookshelf-api.lib.config :as config]
            [bookshelf-api.lib.system :as system]
            [clojure.tools.logging :as log]))

(defn -main [& _]
  (let [settings (config/load-config)
        running (system/start! settings)]
    (if (= "migrate" (:app-role settings))
      (system/stop! running)
      (do
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(system/stop! running) "bookshelf-shutdown"))
        (log/info "Started role" (:app-role settings))
        ;; Jetty / the worker own non-daemon threads; keep main alive too.
        @(promise)))))
