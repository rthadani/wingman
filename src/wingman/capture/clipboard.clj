(ns wingman.capture.clipboard
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor FlavorListener)))

(def ^:private watch (atom nil))
(def ^:private delivered (atom {}))

(defn- read-clipboard [clipboard]
  (try
    (let [contents (.getContents clipboard nil)]
      (when (and contents (.isDataFlavorSupported contents DataFlavor/stringFlavor))
        (let [data (.getTransferData contents DataFlavor/stringFlavor)]
          (when (string? data) data))))
    (catch Exception _ nil)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- prune-delivered! [now]
  (when (> (count @delivered) 100)
    (let [cutoff (- now 600000)]
      (swap! delivered (fn [m]
                         (into {} (filter (fn [[_ ts]]
                                            (>= ts cutoff))
                                          m)))))))

(defn- send-if-new! [send-fn content]
  (let [trimmed (str/trim (or content ""))
        now (now-ms)]
    (when (seq trimmed)
      (let [last-sent (get @delivered trimmed)]
        (when (or (nil? last-sent) (> (- now last-sent) 60000))
          (send-fn trimmed)
          (swap! delivered assoc trimmed now)
          (prune-delivered! now))))))

(defn stop-clipboard-watch! []
  (when-let [{:keys [clipboard listener stop-ch]} @watch]
    (async/close! stop-ch)
    (.removeFlavorListener clipboard listener)
    (reset! watch nil)))

(defn start-clipboard-watch! [send-fn]
  (stop-clipboard-watch!)
  (let [clipboard (-> (Toolkit/getDefaultToolkit) (.getSystemClipboard))
        stop-ch (async/chan)
        l (reify FlavorListener
            (flavorsChanged [_ _]
              (send-if-new! send-fn (read-clipboard clipboard))))]
    (.addFlavorListener clipboard l)
    (async/thread
      (loop []
        (let [[_ ch] (async/alts!! [(async/timeout 500) stop-ch])]
          (when-not (= ch stop-ch)
            (send-if-new! send-fn (read-clipboard clipboard))
            (recur)))))
    (reset! watch {:clipboard clipboard :listener l :stop-ch stop-ch})
    clipboard))
