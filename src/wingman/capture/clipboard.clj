(ns wingman.capture.clipboard
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer DataFlavor FlavorListener)))

(def ^:private watch (atom nil))

(defn- read-clipboard [clipboard]
  (try
    (let [contents (.getContents clipboard nil)]
      (when (and contents (.isDataFlavorSupported contents DataFlavor/stringFlavor))
        (let [data (.getTransferData contents DataFlavor/stringFlavor)]
          (when (string? data) data))))
    (catch Exception _ nil)))

(defn- trimmed-text [content]
  (some-> content str/trim not-empty))

(defn- send-if-changed!
  "Record content as the last seen clipboard text and deliver it when it differs
   from last-seen. Blank content only clears the baseline. Returns the text sent,
   or nil when nothing was delivered."
  [send-fn last-seen content]
  (let [current (trimmed-text content)
        prev @last-seen]
    (when (compare-and-set! last-seen prev current)
      (when (and current (not= current prev))
        (send-fn current)
        current))))

(defn stop-clipboard-watch! []
  (when-let [{:keys [clipboard listener stop-ch]} @watch]
    (async/close! stop-ch)
    (.removeFlavorListener clipboard listener)
    (reset! watch nil)))

(defn start-clipboard-watch! [send-fn]
  (stop-clipboard-watch!)
  (let [clipboard (-> (Toolkit/getDefaultToolkit) (.getSystemClipboard))
        stop-ch (async/chan)
        ;; Clipboard text present at start is the baseline and is never sent.
        last-seen (atom (trimmed-text (read-clipboard clipboard)))
        check! (fn [] (send-if-changed! send-fn last-seen (read-clipboard clipboard)))
        l (reify FlavorListener
            (flavorsChanged [_ _] (check!)))]
    (.addFlavorListener clipboard l)
    (async/thread
      (loop []
        (let [[_ ch] (async/alts!! [(async/timeout 500) stop-ch])]
          (when-not (= ch stop-ch)
            (check!)
            (recur)))))
    (reset! watch {:clipboard clipboard :listener l :stop-ch stop-ch})
    clipboard))
