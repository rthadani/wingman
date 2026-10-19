(ns wingman.capture.core-test
  (:require [clojure.test :refer [deftest is]]
            [wingman.capture.audio :as audio]
            [wingman.capture.core :as core]))

(deftest env-device-names-test
  (is (= ["Mic" "BlackHole"] (core/env-device-names "Mic, BlackHole")))
  (is (= ["Mic"] (core/env-device-names "Mic")))
  (is (= [] (core/env-device-names nil)))
  (is (= [] (core/env-device-names "  "))))

(deftest parse-selection-line-test
  (let [devices [{:name "Mic"} {:name "BlackHole"} {:name "Loopback"}]]
    (is (= ["Mic"] (core/parse-selection-line devices "1")))
    (is (= ["Mic" "Loopback"] (core/parse-selection-line devices "1,3")))
    (is (= ["BlackHole"] (core/parse-selection-line devices "2")))
    (is (= [] (core/parse-selection-line devices "")))
    (is (= [] (core/parse-selection-line devices "99")))
    (is (= [] (core/parse-selection-line devices "abc")))))

(deftest select-devices-env-fallback-test
  (with-redefs [audio/list-devices (fn [] [{:name "Mic"}])]
    (is (= ["a" "b"] (core/select-devices [] nil "a, b")))
    (is (= [] (core/select-devices [] nil nil)))
    (is (= [] (core/select-devices [] nil "  ")))))
