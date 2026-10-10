(ns bookshelf-api.lib.events
  (:require [bookshelf-api.db.queries :as q]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [redis.clients.jedis DefaultJedisClientConfig Jedis JedisPubSub]))

(defn- connect [url]
  (Jedis. (URI. url)
          (.build (doto (DefaultJedisClientConfig/builder)
                    (.timeoutMillis 2000)
                    (.autoNegotiateProtocol false)))))

(defn healthy? [{:keys [redis-url]}]
  (with-open [client (connect redis-url)]
    (= "PONG" (.ping client))))

(defn publisher [{:keys [redis-enabled redis-url redis-channel]}]
  (if-not redis-enabled
    (constantly nil)
    (fn []
      ;; DB changes have committed before this notification. Pub/sub is best effort.
      (try
        (with-open [client (connect redis-url)]
          (.publish client ^String redis-channel "refresh"))
        (catch Exception _
          (log/warn "Redis notification failed; rollup will catch up on worker reconnect"))))))

(defn start-worker! [db {:keys [redis-url redis-channel]}]
  (let [running? (atom true)
        client-ref (atom nil)
        listener (proxy [JedisPubSub] []
                   (onSubscribe [_ _]
                     ;; Subscribe first, then repair changes missed while offline.
                     (q/refresh-counts! db)
                     (log/info "Worker subscribed; rollup refreshed"))
                   (onMessage [_ _]
                     (q/refresh-counts! db)))
        thread (Thread.
                (fn []
                  (while @running?
                    (try
                      (with-open [client (connect redis-url)]
                        (reset! client-ref client)
                        (when @running?
                          (.subscribe client listener (into-array String [redis-channel]))))
                      (catch Exception _
                        (when @running?
                          (log/warn "Worker disconnected or refresh failed; retrying in one second")))
                      (finally (reset! client-ref nil)))
                    (when @running?
                      (try (Thread/sleep 1000) (catch InterruptedException _)))))
                "bookshelf-rollup-worker")]
    (.start thread)
    {:stop! (fn []
              (reset! running? false)
              ;; Disconnect unblocks the dedicated subscriber without waiting for a message.
              (when-let [^Jedis client @client-ref]
                (try (.close client) (catch Exception _)))
              (.interrupt thread)
              (.join thread 7000))}))
