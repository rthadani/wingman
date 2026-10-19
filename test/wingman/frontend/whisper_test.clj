(ns wingman.frontend.whisper-test
  (:require [clojure.test :refer [deftest is]]
            [com.phronemophobic.whisper :as lib]
            [wingman.frontend.whisper :as whisper]))

(deftest transcribe-returns-text-test
  (with-redefs [lib/transcribe-wav (fn [_model _wav] "  one two  ")]
    (is (= {:text "one two" :segments []}
           (whisper/transcribe "/tmp/a.wav" {:model "m.bin"})))))

(deftest transcribe-empty-on-error-test
  (with-redefs [lib/transcribe-wav (fn [_model _wav] (throw (RuntimeException. "boom")))]
    (is (= {:text "" :segments []} (whisper/transcribe "/tmp/a.wav" {:model "m.bin"})))))

(deftest transcribe-single-arity-uses-model-path-test
  (let [model (atom nil)]
    (with-redefs [whisper/model-path (fn [] "m-from-env.bin")
                  lib/transcribe-wav (fn [m _wav] (reset! model m) "hi")]
      (is (= "hi" (:text (whisper/transcribe "/tmp/a.wav"))))
      (is (= "m-from-env.bin" @model)))))

(deftest whisper-available-model-test
  (let [f (java.io.File/createTempFile "model" ".bin")]
    (try
      (with-redefs [whisper/model-path (fn [] (.getAbsolutePath f))]
        (is (true? (whisper/whisper-available?))))
      (with-redefs [whisper/model-path (fn [] nil)]
        (is (false? (whisper/whisper-available?))))
      (with-redefs [whisper/model-path (fn [] "/no/such/model.bin")]
        (is (false? (whisper/whisper-available?))))
      (finally (.delete f)))))
