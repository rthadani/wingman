(ns wingman.frontend.capture
  (:require [wingman.frontend.whisper :as whisper])
  (:import (java.nio ByteBuffer ByteOrder)))

(defn pcm->wav [^bytes pcm-bytes]
  (let [n (alength pcm-bytes)
        bb (ByteBuffer/allocate (+ 44 n))]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.put bb (.getBytes "RIFF"))
    (.putInt bb (+ 36 n))
    (.put bb (.getBytes "WAVE"))
    (.put bb (.getBytes "fmt "))
    (.putInt bb 16)
    (.putShort bb 1)
    (.putShort bb 1)
    (.putInt bb 16000)
    (.putInt bb 32000)
    (.putShort bb 2)
    (.putShort bb 16)
    (.put bb (.getBytes "data"))
    (.putInt bb n)
    (.put bb pcm-bytes)
    (.array bb)))

(defn- sniff-extension [^bytes audio-bytes]
  (if (and (>= (alength audio-bytes) 4)
           (= "RIFF" (String. audio-bytes 0 4)))
    ".wav"
    ".webm"))

(defn process-audio-chunk
  ([audio-bytes] (process-audio-chunk audio-bytes nil))
  ([audio-bytes opts]
   (let [tmp (java.io.File/createTempFile "interview-audio"
                                          (sniff-extension audio-bytes))]
     (try
       (with-open [out (java.io.FileOutputStream. tmp)]
         (.write out ^bytes audio-bytes))
       (let [{:keys [text segments]} (whisper/transcribe (.getAbsolutePath tmp) opts)]
         {:text text
          :segments segments})
       (finally
         (.delete tmp))))))

(defn start-capture-session [session-id]
  {:session-id session-id
   :status :capturing
   :started-at (System/currentTimeMillis)})

(defn stop-capture-session [session]
  (assoc session :status :stopped
         :stopped-at (System/currentTimeMillis)))
