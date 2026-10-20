(ns wingman.capture.clipboard
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor FlavorListener)))

(def ^:private watch (atom nil))
(def ^:private delivered (atom #{}))

(defn- read-clipboard [clipboard]
  (try
    (let [contents (.getContents clipboard nil)]
      (when (and contents (.isDataFlavorSupported contents DataFlavor/stringFlavor))
        (let [data (.getTransferData contents DataFlavor/stringFlavor)]
          (when (string? data) data))))
    (catch Exception _ nil)))

(defn- send-if-new! [send-fn content]
  (let [trimmed (str/trim (or content ""))]
    (when (and (seq trimmed)
               (not (contains? @delivered trimmed)))
      (swap! delivered conj trimmed)
      (send-fn trimmed))))

(defn start-clipboard-watch! [send-fn]
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

(defn stop-clipboard-watch! []
  (when-let [{:keys [clipboard listener stop-ch]} @watch]
    (async/close! stop-ch)
    (.removeFlavorListener clipboard listener)
    (reset! watch nil)))
