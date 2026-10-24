(ns wingman.capture.core-test
  (:require [clojure.test :refer [deftest is]]
            [wingman.capture.audio :as audio]
            [wingman.capture.core :as core]))

(deftest env-device-names-test
  (is (= [{:name "Mic" :speaker nil} {:name "BlackHole" :speaker nil}]
         (core/env-device-names "Mic, BlackHole")))
  (is (= [{:name "Mic" :speaker nil}] (core/env-device-names "Mic")))
  (is (= [] (core/env-device-names nil)))
  (is (= [] (core/env-device-names "  ")))
  (is (= [{:name "plughw:3" :speaker :interviewer}]
         (core/env-device-names "plughw:3=interviewer")))
  (is (= [{:name "mic" :speaker nil} {:name "plughw:3" :speaker :interviewer}]
         (core/env-device-names "mic,plughw:3=interviewer")))
  (is (= [{:name "mic" :speaker :interviewer} {:name "plughw:3" :speaker nil}]
         (core/env-device-names "mic=interviewer,plughw:3")))
  (is (= [{:name "Mic" :speaker :candidate}]
         (core/env-device-names "Mic=CANDIDATE")))
  (is (= [{:name "plughw:4,0" :speaker :candidate}
          {:name "plughw:3,0" :speaker :interviewer}]
         (core/env-device-names "plughw:4,0=candidate,plughw:3,0=interviewer")))
  (is (= [{:name "plughw:4" :speaker :candidate}
          {:name "plughw:3" :speaker :interviewer}]
         (core/env-device-names "plughw:4=candidate,plughw:3=interviewer")))
  (is (= [{:name "plughw:4,0" :speaker nil}
          {:name "plughw:3,0" :speaker nil}]
         (core/env-device-names "plughw:4,0,plughw:3,0"))))

(deftest parse-selection-line-test
  (let [devices [{:name "Mic"} {:name "BlackHole"} {:name "Loopback"}]]
    (is (= [{:name "Mic" :speaker nil}] (core/parse-selection-line devices "1")))
    (is (= [{:name "Mic" :speaker nil} {:name "Loopback" :speaker nil}]
           (core/parse-selection-line devices "1,3")))
    (is (= [{:name "BlackHole" :speaker nil}] (core/parse-selection-line devices "2")))
    (is (= [] (core/parse-selection-line devices "")))
    (is (= [] (core/parse-selection-line devices "99")))
    (is (= [] (core/parse-selection-line devices "abc")))))

(deftest select-devices-env-fallback-test
  (with-redefs [audio/list-devices (fn [] [{:name "Mic"}])]
    (is (= [{:name "a" :speaker nil} {:name "b" :speaker nil}]
           (core/select-devices [] nil "a, b")))
    (is (= [] (core/select-devices [] nil nil)))
    (is (= [] (core/select-devices [] nil "  ")))))
