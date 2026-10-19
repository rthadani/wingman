(ns wingman.capture.transport-test
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [wingman.capture.transport :as transport])
  (:import (java.util.concurrent CompletableFuture)))

(deftest send-clipboard-update-test
  (let [calls (atom [])
        ws (reify java.net.http.WebSocket
             (sendText [_ text last?]
               (swap! calls conj [text last?])
               (CompletableFuture/completedFuture nil)))]
    (transport/send-clipboard-update ws "hello world")
    (is (= 1 (count @calls)))
    (let [[text last?] (first @calls)]
      (is (true? last?))
      (is (= {:type "clipboard.update" :payload "hello world"}
             (json/parse-string text true))))))

(deftest start-sender-order-test
  (let [calls (atom [])
        ws (reify java.net.http.WebSocket
             (sendBinary [_ buf last?]
               (swap! calls conj [:binary (.remaining buf) last?])
               (CompletableFuture/completedFuture nil))
             (sendText [_ text last?]
               (swap! calls conj [:text text last?])
               (CompletableFuture/completedFuture nil)))
        ws-atom (atom {:ws ws :closed (async/chan)})
        ch (transport/start-sender! ws-atom)]
    (is (true? (async/>!! ch [:audio (.getBytes "abc") :candidate])))
    (is (true? (async/>!! ch [:clipboard "x"])))
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (while (and (< (count @calls) 2) (< (System/currentTimeMillis) deadline))
        (async/<!! (async/timeout 25))))
    (is (= [[:binary 4 true]
            [:text (json/generate-string {:type "clipboard.update" :payload "x"}) true]]
           @calls))))

(deftest send-audio-segment-prepends-speaker-byte-test
  (let [calls (atom [])
        ws (reify java.net.http.WebSocket
             (sendBinary [_ buf last?]
               (let [b (byte-array (.remaining buf))]
                 (.get buf b)
                 (swap! calls conj (vec b))
                 (CompletableFuture/completedFuture nil))))]
    (transport/send-audio-segment ws (byte-array [1 2 3]) :interviewer)
    (is (= [1 1 2 3] (first @calls)))
    (transport/send-audio-segment ws (byte-array [4 5]) :candidate)
    (is (= [0 4 5] (second @calls)))))

(deftest backoff-delay-test
  (is (= 1000 (transport/backoff-delay 0)))
  (is (= 2000 (transport/backoff-delay 1)))
  (is (= 4000 (transport/backoff-delay 2)))
  (is (= 8000 (transport/backoff-delay 3)))
  (is (= 16000 (transport/backoff-delay 4)))
  (is (= 30000 (transport/backoff-delay 5)))
  (is (= 30000 (transport/backoff-delay 99))))
