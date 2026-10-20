(ns wingman.capture.clipboard-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [wingman.capture.clipboard :as clipboard])
  (:import (java.awt Toolkit)
           (java.awt.datatransfer StringSelection)))

(def ^:private delivered #'clipboard/delivered)

(defn- reset-state [f]
  (reset! @delivered #{})
  (try (f) (finally (clipboard/stop-clipboard-watch!))))

(use-fixtures :each reset-state)

(deftest send-if-new-delivers-new-content-test
  (let [sent (atom [])]
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "first")
    (is (= ["first"] @sent))
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "first")
    (is (= ["first"] @sent))
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "second")
    (is (= ["first" "second"] @sent))))

(deftest send-if-new-trims-and-dedupes-test
  (let [sent (atom [])]
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "  link  ")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "link\n")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "\tlink")
    (is (= ["link"] @sent))
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "other")
    (is (= ["link" "other"] @sent))))

(deftest send-if-new-skips-nil-and-whitespace-test
  (let [sent (atom [])]
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) nil)
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "   ")
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "\n\t")
    (is (= [] @sent))))

(deftest cross-arm-dedupe-test
  (let [sent (atom [])]
    ;; first arm
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "shared-text")
    (is (= ["shared-text"] @sent))
    ;; second arm (different send-fn, same process lifetime)
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "shared-text")
    (is (= ["shared-text"] @sent))
    ;; new text still flows
    (#'clipboard/send-if-new! (fn [c] (swap! sent conj c)) "new-text")
    (is (= ["shared-text" "new-text"] @sent))))

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
