(ns wingman.capture.clipboard-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is use-fixtures]]
            [wingman.capture.clipboard :as clipboard]))

(def ^:private watch @#'clipboard/watch)

(defn- reset-watch [f]
  (try (f) (finally (clipboard/stop-clipboard-watch!))))

(use-fixtures :each reset-watch)

(defn- await-sends
  "Wait up to 3s for sent to hold at least n clipboard texts."
  [sent n]
  (loop [waited 0]
    (when (and (< (count @sent) n) (< waited 3000))
      (Thread/sleep 50)
      (recur (+ waited 50)))))

(deftest send-if-changed-trims-text-and-skips-blank-test
  (let [sent (atom [])
        last-seen (atom nil)
        send! (fn [c] (swap! sent conj c))]
    (#'clipboard/send-if-changed! send! last-seen "  link  ")
    (is (= ["link"] @sent) "surrounding whitespace is trimmed")
    (#'clipboard/send-if-changed! send! last-seen "link\n")
    (#'clipboard/send-if-changed! send! last-seen "\tlink")
    (is (= ["link"] @sent) "the same trimmed text is not resent")
    (#'clipboard/send-if-changed! send! last-seen "   ")
    (#'clipboard/send-if-changed! send! last-seen nil)
    (is (= ["link"] @sent) "blank content is never sent")
    (#'clipboard/send-if-changed! send! last-seen "other")
    (is (= ["link" "other"] @sent) "changed content is sent")))

(deftest clipboard-text-present-at-start-is-not-sent-test
  (let [clip (atom "text copied before the session started")
        sent (atom [])]
    (with-redefs [clipboard/read-clipboard (fn [_] @clip)]
      (clipboard/start-clipboard-watch! (fn [c] (swap! sent conj c)))
      (Thread/sleep 1200)
      (is (= [] @sent) "clipboard text from before the watch is never sent"))))

(deftest clipboard-change-is-sent-once-test
  (let [clip (atom "text copied before the session started")
        sent (atom [])]
    (with-redefs [clipboard/read-clipboard (fn [_] @clip)]
      (clipboard/start-clipboard-watch! (fn [c] (swap! sent conj c)))
      (Thread/sleep 600)
      (reset! clip "new question")
      (await-sends sent 1)
      (is (= ["new question"] @sent)))))

(deftest unchanged-clipboard-text-is-not-resent-test
  (let [clip (atom "stable text")
        sent (atom [])]
    (with-redefs [clipboard/read-clipboard (fn [_] @clip)]
      (clipboard/start-clipboard-watch! (fn [c] (swap! sent conj c)))
      (Thread/sleep 600)
      (reset! clip "changed text")
      (await-sends sent 1)
      (Thread/sleep 1200)
      (is (= ["changed text"] @sent) "no timer resend while the clipboard is unchanged"))))

(deftest blank-clipboard-text-is-not-sent-test
  (let [clip (atom nil)
        sent (atom [])]
    (with-redefs [clipboard/read-clipboard (fn [_] @clip)]
      (clipboard/start-clipboard-watch! (fn [c] (swap! sent conj c)))
      (Thread/sleep 600)
      (reset! clip "   \n\t ")
      (Thread/sleep 1200)
      (is (= [] @sent) "blank clipboard text is never sent")
      (reset! clip "real text")
      (await-sends sent 1)
      (is (= ["real text"] @sent)))))

(deftest start-clipboard-watch-replaces-prior-watcher-test
  (with-redefs [clipboard/read-clipboard (constantly nil)]
    (clipboard/start-clipboard-watch! (fn [_]))
    (let [first-watcher @watch
          first-stop-ch (:stop-ch first-watcher)]
      (is (some? first-watcher))
      (clipboard/start-clipboard-watch! (fn [_]))
      (is (some? @watch))
      (is (not= first-watcher @watch) "new watcher replaced the old one")
      (let [closed? (async/alt!! first-stop-ch ([_] true)
                                 (async/timeout 100) ([_] false))]
        (is closed? "prior watcher stop-ch was closed")))))
