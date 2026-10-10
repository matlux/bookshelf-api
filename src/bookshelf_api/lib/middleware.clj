(ns bookshelf-api.lib.middleware
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import [java.util UUID]
           [java.util.concurrent Callable ExecutionException ExecutorService
            RejectedExecutionException SynchronousQueue ThreadFactory
            ThreadPoolExecutor TimeUnit TimeoutException]))

(defn executor [threads]
  ;; Bound in-flight work: reject excess requests instead of accumulating a queue.
  (ThreadPoolExecutor. threads threads 0 TimeUnit/MILLISECONDS (SynchronousQueue.)
                       (reify ThreadFactory
                         (newThread [_ task]
                           (doto (Thread. task "bookshelf-request") (.setDaemon true))))))

(defn- json-error [status message]
  {:status status :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string {:error message})})

(def request-id
  {:name ::request-id
   :wrap (fn [handler]
           (fn [request]
             (let [supplied (get-in request [:headers "x-request-id"])
                   id (if (and (string? supplied)
                               (re-matches #"[A-Za-z0-9._-]{1,128}" supplied))
                        supplied (str (UUID/randomUUID)))]
               (assoc-in (handler (assoc request :request-id id))
                         [:headers "X-Request-ID"] id))))})

(def access-log
  {:name ::access-log
   :wrap (fn [handler]
           (fn [request]
             (let [start (System/nanoTime)
                   response (handler request)]
               ;; Query strings, bodies, and credentials are deliberately omitted.
               (log/info (pr-str {:request-id (:request-id request)
                                 :method (name (:request-method request))
                                 :path (:uri request) :status (:status response)
                                 :duration-ms (/ (- (System/nanoTime) start) 1e6)}))
               response)))})

(defn cors [origins]
  {:name ::cors
   :wrap
   (fn [handler]
     (fn [request]
       (let [origin (get-in request [:headers "origin"])
             preflight? (and (= :options (:request-method request)) origin
                             (get-in request [:headers "access-control-request-method"]))
             allowed? (contains? (set origins) origin)
             method (get-in request [:headers "access-control-request-method"])
             headers (->> (str/split (get-in request [:headers "access-control-request-headers"] "") #",")
                          (map (comp str/lower-case str/trim)) (remove str/blank?))
             valid? (and allowed? (contains? #{"GET" "POST" "PUT" "DELETE" "OPTIONS"} method)
                         (every? #{"content-type" "x-request-id"} headers))
             response (cond
                        preflight? (if valid? {:status 204 :body nil}
                                       (json-error 403 "CORS preflight denied"))
                        :else (handler request))
             vary (if preflight? "Origin, Access-Control-Request-Method, Access-Control-Request-Headers" "Origin")]
         (cond-> (update-in response [:headers "Vary"]
                            #(if (str/blank? %) vary (str % ", " vary)))
           (and allowed? (or (not preflight?) valid?))
           (update :headers merge {"Access-Control-Allow-Origin" origin
                                   "Access-Control-Expose-Headers" "X-Request-ID, Location"})
           (and preflight? valid?)
           (update :headers merge {"Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                                   "Access-Control-Allow-Headers" "Content-Type, X-Request-ID"})))))})

(defn request-timeout [^ExecutorService executor timeout-ms]
  {:name ::request-timeout
   :wrap
   (fn [handler]
     (fn [request]
       (try
         (let [task (.submit executor ^Callable (bound-fn [] (handler request)))]
           (try
             (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
             (catch TimeoutException _
               (.cancel task true)
               (json-error 504 "Request timed out"))
             (catch InterruptedException error
               (.cancel task true)
               (.interrupt (Thread/currentThread))
               (throw error))
             (catch ExecutionException error
               (throw (.getCause error)))))
         (catch RejectedExecutionException _
           (json-error 503 "Request capacity exhausted")))))})
