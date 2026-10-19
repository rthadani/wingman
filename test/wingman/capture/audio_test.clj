(ns wingman.capture.audio-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.capture.audio :as audio])
  (:import (be.tarsos.dsp AudioEvent SilenceDetector)
           (be.tarsos.dsp.io TarsosDSPAudioFormat)
           (java.nio ByteBuffer ByteOrder)))

(deftest silence-detector-default-threshold-test
  (with-redefs [audio/env (constantly nil)]
    (is (= -25.0 (audio/silence-threshold-db))))
  (with-redefs [audio/env (constantly "  ")]
    (is (= -25.0 (audio/silence-threshold-db)))))

(deftest silence-detector-env-threshold-test
  (with-redefs [audio/env (constantly "-40.5")]
    (is (= -40.5 (audio/silence-threshold-db)))))

(deftest floats->pcm-s16le-test
  (let [bytes (audio/floats->pcm-s16le (float-array [0.0 0.5 -1.0 2.0]))]
    (is (= 8 (count bytes)))
    (let [bb (ByteBuffer/wrap bytes)]
      (.order bb ByteOrder/LITTLE_ENDIAN)
      (is (= 0 (.getShort bb)))
      (is (= 16383 (.getShort bb)))
      (is (= -32767 (.getShort bb)))
      (is (= 32767 (.getShort bb)))))
  (is (= 0 (count (audio/floats->pcm-s16le [])))))

(deftest step-accumulates-during-speech-test
  (let [b (float-array [0.5 0.25])
        r (audio/step [] false b 470)]
    (is (nil? (:flush r)))
    (is (= 1 (count (:acc r))))
    (is (not (identical? b (first (:acc r)))))))

(deftest step-flushes-on-silence-edge-test
  (let [b (float-array [0.5 0.25])
        r (audio/step [(aclone b)] true b 470)]
    (is (= [0.5 0.25] (:flush r)))
    (is (= [] (:acc r)))))

(deftest step-noop-when-silent-and-empty-test
  (let [r (audio/step [] true (float-array [0.0]) 470)]
    (is (nil? (:flush r)))
    (is (= [] (:acc r)))))

(deftest step-force-flushes-at-cap-test
  (let [b (float-array [0.5 0.25])
        r (audio/step (vec (repeat 3 (aclone b))) false b 3)]
    (is (= [0.5 0.25 0.5 0.25 0.5 0.25] (:flush r)))
    (is (= 1 (count (:acc r))))))

(defn- audio-event [n amp]
  (let [ev (AudioEvent. (TarsosDSPAudioFormat. 16000.0 16 1 true false))]
    (.setFloatBuffer ev (float-array (repeat n amp)))
    ev))

(deftest utterance-processor-state-machine-test
  (let [detector (SilenceDetector. -25.0 true)
        sent (atom [])
        p (audio/utterance-processor detector (fn [pcm] (swap! sent conj pcm)))]
    (is (true? (.process p (audio-event 128 0.9))))
    (is (true? (.process p (audio-event 128 0.9))))
    (is (empty? @sent))
    (.process p (audio-event 128 0.0))
    (is (= 1 (count @sent)))
    (is (= 512 (alength (first @sent))))
    (.process p (audio-event 128 0.9))
    (.processingFinished p)
    (is (= 2 (count @sent)))
    (is (= 256 (alength (second @sent))))))

(deftest start-audio-capture-multi-device-test
  (with-redefs [audio/dispatcher-for (fn [_send-fn {:keys [mixer]}] (or mixer :default))]
    (is (= [:default] (audio/start-audio-capture! identity [{:mixer nil :speaker :candidate}])))
    (is (= [:m1 :m2] (audio/start-audio-capture! identity [{:mixer :m1 :speaker :candidate} {:mixer :m2 :speaker :interviewer}])))))

(deftest list-devices-returns-vector-test
  (is (vector? (audio/list-devices))))

(deftest find-mixer-blank-and-unknown-test
  (is (nil? (audio/find-mixer nil)))
  (is (nil? (audio/find-mixer "")))
  (is (nil? (audio/find-mixer "   ")))
  (is (nil? (audio/find-mixer "definitely-not-a-real-audio-device-xyz"))))

(deftest find-mixer-substring-test
  (let [devices (audio/list-devices)]
    (when (seq devices)
      (let [{:keys [name]} (first devices)
            substr (subs name 0 (min 3 (count name)))]
        (is (some? (audio/find-mixer substr)))
        (is (some? (audio/find-mixer (str/lower-case substr))))
        (is (some? (audio/find-mixer (str/upper-case substr))))))))

(deftest downmix-averages-channels-test
  (let [stereo (float-array [0.5 -0.5 1.0 0.0 -1.0 -1.0])
        mono ((var audio/downmix) stereo 2)]
    (is (= 3 (alength mono)))
    (is (< (Math/abs (- 0.0 (aget mono 0))) 0.0001))
    (is (< (Math/abs (- 0.5 (aget mono 1))) 0.0001))
    (is (< (Math/abs (- -1.0 (aget mono 2))) 0.0001))))

(deftest resample-to-target-16k-pass-through-test
  (let [buf (float-array (repeat 1024 0.5))
        out ((var audio/resample-to-target) buf 16000.0)]
    (is (identical? buf out))))

(deftest resample-to-target-downsamples-48k-test
  (let [buf (float-array (repeat 4800 0.5))
        out ((var audio/resample-to-target) buf 48000.0)]
    (is (<= 1500 (alength out) 1700))))
