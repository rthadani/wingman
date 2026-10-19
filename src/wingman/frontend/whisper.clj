(ns wingman.frontend.whisper
  (:require [com.phronemophobic.whisper :as whisper]
            [clojure.string :as str]))

(defn model-path []
  (System/getenv "WHISPER_MODEL"))

(defn whisper-available?
  ([] (whisper-available? (model-path)))
  ([model]
   (boolean (and model (.exists (java.io.File. ^String model))))))

(defn transcribe
  ([audio-path] (transcribe audio-path nil))
  ([audio-path {:keys [model]}]
   (try
     (let [text (str/trim (whisper/transcribe-wav (or model (model-path)) audio-path))]
       {:text text :segments []})
     (catch Exception _ {:text "" :segments []}))))
