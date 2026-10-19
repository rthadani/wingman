(ns wingman.frontend.sse-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.sse :as sse]
            [org.httpkit.server :as http-kit]))

(deftest unregister-client-removes-only-target-test
  (let [registry (sse/new-registry)
        c1 (Object.)
        c2 (Object.)
        sent (atom [])]
    (with-redefs [http-kit/send! (fn [ch msg & _] (swap! sent conj [ch msg]))
                  http-kit/close (fn [_ch] nil)]
      (sse/register-client registry "s" c1)
      (sse/register-client registry "s" c2)
      (sse/unregister-client registry "s" c1)
      (sse/broadcast registry "s" "assistant" "payload")
      (is (not (some #(identical? c1 (first %)) @sent)))
      (let [f2 (some #(when (identical? c2 (first %)) (second %)) @sent)]
        (is (str/includes? (str f2) "event: assistant"))
        (is (str/includes? (str f2) "data: payload")))
      (sse/unregister-client registry "s" c2)
      (is (not (contains? @registry "s"))))))

(deftest sse-frame-escapes-newlines-test
  (let [registry (sse/new-registry)
        c (Object.)
        sent (atom [])]
    (with-redefs [http-kit/send! (fn [ch msg & _] (swap! sent conj [ch msg]))
                  http-kit/close (fn [_ch] nil)]
      (sse/register-client registry "s" c)
      (sse/broadcast registry "s" "assistant" "line1\nline2\n```python\nprint('hi')\n```")
      (let [frame (second (first @sent))]
        (is (str/includes? frame "event: assistant"))
        (is (str/includes? frame "data: line1&#10;line2&#10;```python&#10;print('hi')&#10;```"))
        ;; must be a single data: line (only one data: prefix, no bare continuation lines)
        (is (= 1 (count (re-seq #"(?m)^data: " frame))))))))

(deftest unregister-empty-session-dissocs-key-test
  (let [registry (sse/new-registry)
        c (Object.)
        sent (atom [])]
    (with-redefs [http-kit/send! (fn [ch msg & _] (swap! sent conj [ch msg]))
                  http-kit/close (fn [_ch] nil)]
      (sse/register-client registry "s" c)
      (sse/unregister-client registry "s" c)
      (is (empty? @registry))
      (sse/broadcast registry "s" "assistant" "nope")
      (is (empty? @sent)))))
