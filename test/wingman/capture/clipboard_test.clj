(ns wingman.capture.clipboard-test
  (:require [clojure.test :refer [deftest is]]
            [wingman.capture.clipboard :as clipboard])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer StringSelection)))

(deftest send-if-new-delivers-new-content-test
  (let [state {:last-sent (atom nil) :sent-set (atom #{})}
        sent (atom [])]
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "first")
    (is (= ["first"] @sent))
    (is (= "first" @(:last-sent state)))
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "first")
    (is (= ["first"] @sent))
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "second")
    (is (= ["first" "second"] @sent))
    (is (= "second" @(:last-sent state)))))

(deftest send-if-new-trims-and-dedupes-test
  (let [state {:last-sent (atom nil) :sent-set (atom #{})}
        sent (atom [])]
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "  link  ")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "link\n")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "\tlink")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "other")
    (is (= ["link" "other"] @sent))))

(deftest send-if-new-skips-nil-and-whitespace-test
  (let [state {:last-sent (atom nil) :sent-set (atom #{})}
        sent (atom [])]
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) nil)
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "   ")
    (#'clipboard/send-if-new! state (fn [c] (swap! sent conj c)) "\n\t")
    (is (= [] @sent))
    (is (nil? @(:last-sent state)))))

(deftest pre-arm-clipboard-is-sent-on-first-poll-test
  (let [clipboard (-> (Toolkit/getDefaultToolkit) (.getSystemClipboard))
        original (.getContents clipboard nil)
        marker "wingman-pre-arm-marker"
        sent (atom nil)]
    (try
      (.setContents clipboard (StringSelection. marker) nil)
      (clipboard/start-clipboard-watch! (fn [c] (reset! sent c)))
      (loop [n 0]
        (when (and (nil? @sent) (< n 30))
          (Thread/sleep 100)
          (recur (inc n))))
      (is (= marker @sent))
      (finally
        (clipboard/stop-clipboard-watch!)
        (when original
          (.setContents clipboard original nil))))))
