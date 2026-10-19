(ns wingman.frontend.capture-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [wingman.frontend.capture :as capture]
            [wingman.frontend.whisper :as whisper]))

(defn stub-transcribe [f]
  (with-redefs [whisper/transcribe (fn [_ _] {:text "hello" :segments []})]
    (f)))

(use-fixtures :each stub-transcribe)

(deftest whisper-available-false-test
  (with-redefs [whisper/model-path (fn [] nil)]
    (is (false? (whisper/whisper-available?)))))

(deftest process-chunk-returns-map-test
  (let [result (capture/process-audio-chunk (byte-array [1 2 3]))]
    (is (map? result))
    (is (contains? result :text))
    (is (= "hello" (:text result)))))

(deftest pcm->wav-test
  (let [pcm (byte-array [1 2 3 4])
        wav (capture/pcm->wav pcm)
        bb (java.nio.ByteBuffer/wrap wav)]
    (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
    (is (= 48 (count wav)))
    (is (= "RIFF" (String. wav 0 4)))
    (is (= "WAVE" (String. wav 8 4)))
    (is (= (vec pcm) (vec (drop 44 wav))))
    (is (= 4 (long (.getInt bb 40))))
    (is (= 16000 (long (.getInt bb 24))))
    (is (= 32000 (long (.getInt bb 28))))
    (is (= 1 (long (.getShort bb 20))))
    (is (= 2 (long (.getShort bb 32))))
    (is (= 16 (long (.getShort bb 34))))))

(deftest process-chunk-sniffs-extension-test
  (let [wav-path (promise)]
    (with-redefs [whisper/transcribe (fn [p _opts]
                                       (deliver wav-path p)
                                       {:text "" :segments []})]
      (capture/process-audio-chunk (capture/pcm->wav (byte-array [1 2 3 4])))
      (is (str/ends-with? (deref wav-path 2000 nil) ".wav"))))
  (let [webm-path (promise)]
    (with-redefs [whisper/transcribe (fn [p _opts]
                                       (deliver webm-path p)
                                       {:text "" :segments []})]
      (capture/process-audio-chunk (byte-array [9 9 9]))
      (is (str/ends-with? (deref webm-path 2000 nil) ".webm")))))
