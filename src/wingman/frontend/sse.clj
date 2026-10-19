(ns wingman.frontend.sse
  (:require [clojure.string :as str]
            [org.httpkit.server :as http-kit]))

(defn new-registry
  []
  (atom {}))

(defn register-client
  [registry session-id client]
  (swap! registry update session-id (fnil conj []) client))

(defn unregister-client
  [registry session-id client]
  (swap! registry
         (fn [m]
           (let [cs (vec (remove #(identical? client %) (get m session-id)))]
             (if (empty? cs)
               (dissoc m session-id)
               (assoc m session-id cs))))))

(defn- sse-frame
  [event data]
  (str "event: " event "\ndata: " (str/replace data "\n" "&#10;") "\n\n"))

(defn stream-headers!
  [ch]
  (http-kit/send! ch {:status 200
                      :headers {"Content-Type" "text/event-stream"
                                "Cache-Control" "no-cache"}}
                  false))

(defn broadcast
  [registry session-id event data]
  (let [frame (sse-frame event data)]
    (doseq [client (get @registry session-id)]
      (try
        (http-kit/send! client frame false)
        (catch Exception _
          (unregister-client registry session-id client)
          (http-kit/close client))))))
