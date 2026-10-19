(ns wingman.capture.transport
  (:require [cheshire.core :as json]
            [clojure.core.async :as async])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket$Listener)
           (java.nio ByteBuffer)))

(defn connect!
  [ws-url ^HttpClient client]
  (let [closed (async/promise-chan)
        listener (reify WebSocket$Listener
                   (onClose [_ _ws _status _reason]
                     (async/put! closed true)
                     nil)
                   (onError [_ _ws _err]
                     (async/put! closed false)
                     nil))
        ws (-> client
               (.newWebSocketBuilder)
               (.buildAsync (URI/create ws-url) listener)
               deref)]
    {:ws ws :closed closed}))

(defn backoff-delay [attempt]
  (min 30000 (bit-shift-left 1000 (min attempt 5))))

(defn keep-connected!
  [ws-atom ws-url client stop-ch]
  (async/thread
    (loop [attempt 0]
      (let [timeout (async/timeout (backoff-delay attempt))
            [_ ch] (async/alts!! [stop-ch timeout])]
        (when-not (= ch stop-ch)
          (let [started (System/currentTimeMillis)]
            (try
              (let [conn (connect! ws-url client)]
                (reset! ws-atom conn)
                (async/alts!! [(:closed conn) stop-ch])
                (reset! ws-atom nil))
              (catch Exception e
                (binding [*out* *err*]
                  (println "connect attempt" (inc attempt) "failed:" (.getMessage e)))))
            (let [stable? (> (- (System/currentTimeMillis) started) 30000)]
              (recur (if stable? 0 (inc attempt))))))))
    nil))

(defn send-audio-segment [ws pcm-bytes speaker]
  (let [n (alength ^bytes pcm-bytes)
        buf (ByteBuffer/allocate (inc n))]
    (.put buf (byte (case speaker :interviewer 1 0)))
    (.put buf ^bytes pcm-bytes)
    (.flip buf)
    (.sendBinary ws buf true)))

(defn send-clipboard-update [ws content]
  (.sendText ws (json/generate-string {:type "clipboard.update" :payload content}) true))

(defn start-sender! [ws-atom]
  (let [ch (async/chan (async/dropping-buffer 64))]
    (async/thread
      (loop []
        (when-let [[tag a b] (async/<!! ch)]
          (when-let [conn (loop [n 0]
                            (or @ws-atom
                                (when (< n 300)
                                  (async/<!! (async/timeout 100))
                                  (recur (inc n)))))]
            (try
              (let [fut (case tag
                          :audio (send-audio-segment (:ws conn) a b)
                          :clipboard (send-clipboard-update (:ws conn) a))]
                (when (= ::timeout (deref fut 15000 ::timeout))
                  (.abort ^java.net.http.WebSocket (:ws conn))))
              (catch Exception _)))
          (recur))))
    ch))
