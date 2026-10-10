(ns bookshelf-api.redis-proxy
  "A test-only TCP proxy: drop Redis connectivity without affecting other users."
  (:require [clojure.java.io :as io])
  (:import [java.net InetSocketAddress ServerSocket Socket URI]))

(defn- daemon [f]
  (doto (Thread. ^Runnable f "test-redis-proxy") (.setDaemon true) (.start)))

(defn start! [redis-url]
  (let [uri (URI. redis-url)
        sockets (atom #{})
        server (atom nil)
        port (atom 0)
        close-socket! (fn [socket]
                        (try (.close ^Socket socket) (catch Exception _))
                        (swap! sockets disj socket))
        pump! (fn [source destination]
                (daemon #(try
                           (io/copy (.getInputStream ^Socket source)
                                    (.getOutputStream ^Socket destination))
                           (catch Exception _)
                           (finally (close-socket! source) (close-socket! destination)))))
        start! (fn []
                 (let [listener (doto (ServerSocket.)
                                  (.setReuseAddress true)
                                  (.bind (InetSocketAddress. "127.0.0.1" (int @port))))]
                   (reset! port (.getLocalPort listener))
                   (reset! server listener)
                   (daemon #(while (not (.isClosed listener))
                              (try
                                (let [client (.accept listener)
                                      upstream (Socket. (.getHost uri)
                                                        (if (neg? (.getPort uri)) 6379 (.getPort uri)))]
                                  (swap! sockets into [client upstream])
                                  (pump! client upstream)
                                  (pump! upstream client))
                                (catch Exception _))))))
        stop! (fn []
                (when-let [^ServerSocket listener @server] (.close listener))
                (doseq [socket @sockets] (close-socket! socket)))]
    (start!)
    {:url (str (URI. "redis" (.getUserInfo uri) "127.0.0.1" @port (.getPath uri) nil nil))
     :stop! stop! :start! start!}))
