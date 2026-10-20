(ns wingman.capture.audio
  (:require [clojure.string :as str])
  (:import (be.tarsos.dsp AudioDispatcher AudioEvent AudioProcessor SilenceDetector)
           (be.tarsos.dsp.io TarsosDSPAudioFormat)
           (be.tarsos.dsp.io.jvm AudioDispatcherFactory JVMAudioInputStream)
           (be.tarsos.dsp.resample Resampler)
           (java.util Arrays)
           (javax.sound.sampled AudioFormat AudioInputStream AudioSystem DataLine$Info LineUnavailableException Mixer TargetDataLine)
           (java.nio ByteBuffer ByteOrder)))

(defn env [var-name]
  (System/getenv var-name))

(defn silence-threshold-db []
  (or (some-> (env "SILENCE_THRESHOLD_DB") str/trim not-empty parse-double)
      -25.0))

(defn floats->pcm-s16le [floats]
  (let [n (count floats)
        bb (ByteBuffer/allocate (* 2 n))]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (dotimes [i n]
      (let [v (max -1.0 (min 1.0 (double (nth floats i))))]
        (.putShort bb (short (* 32767.0 v)))))
    (.array bb)))

(def force-flush-buffers 470)

(defn step [acc silence? buf cap]
  (cond
    silence? (if (seq acc)
               {:flush (into [] cat acc) :acc []}
               {:flush nil :acc acc})
    (>= (count acc) cap) {:flush (into [] cat acc) :acc [(aclone buf)]}
    :else {:flush nil :acc (conj acc (aclone buf))}))

(defn utterance-processor [detector send-fn]
  (let [acc (atom [])]
    (reify AudioProcessor
      (process [_ event]
        (let [buf (.getFloatBuffer ^AudioEvent event)
              {:keys [flush] acc' :acc} (step @acc (.isSilence ^SilenceDetector detector buf) buf force-flush-buffers)]
          (when flush
            (send-fn (floats->pcm-s16le flush)))
          (reset! acc acc'))
        true)
      (processingFinished [_]
        (when (seq @acc)
          (send-fn (floats->pcm-s16le (into [] cat @acc)))
          (reset! acc []))))))

(def ^:private target-format
  (AudioFormat. (float 16000) 16 1 true false))

(def ^:private capture-formats
  [(AudioFormat. (float 48000) 16 2 true false)
   (AudioFormat. (float 48000) 16 1 true false)
   (AudioFormat. (float 44100) 16 2 true false)
   (AudioFormat. (float 44100) 16 1 true false)
   target-format])

(defn list-devices []
  (into []
        (keep (fn [mi]
                (let [mixer (AudioSystem/getMixer mi)]
                  (when (some #(.isLineSupported mixer (DataLine$Info. TargetDataLine %)) capture-formats)
                    {:name (.getName mi)
                     :description (.getDescription mi)}))))
        (AudioSystem/getMixerInfo)))

(defn find-mixer [name-or-desc]
  (let [t (str/lower-case (str/trim (or name-or-desc "")))]
    (when-not (str/blank? t)
      (or (some (fn [mi]
                  (when (or (= t (str/lower-case (.getName mi)))
                            (= t (str/lower-case (.getDescription mi))))
                    (AudioSystem/getMixer mi)))
                (AudioSystem/getMixerInfo))
          (some (fn [mi]
                  (when (or (str/includes? (str/lower-case (.getName mi)) t)
                            (str/includes? (str/lower-case (.getDescription mi)) t))
                    (AudioSystem/getMixer mi)))
                (AudioSystem/getMixerInfo))))))

(defn- downmix [^floats buf ^long channels]
  (let [frames (/ (alength buf) channels)
        out (float-array frames)]
    (dotimes [i frames]
      (aset out i
            (float (/ (loop [c 0 sum 0.0]
                        (if (< c channels)
                          (recur (inc c) (+ sum (aget buf (+ (* i channels) c))))
                          sum))
                      channels))))
    out))

(defn- resample-to-target [^floats buf source-rate]
  (if (== source-rate 16000.0)
    buf
    (let [factor (/ 16000.0 source-rate)
          estimated (max 1 (int (Math/ceil (* (alength buf) factor))))
          out (float-array estimated)
          resampler (Resampler. false 0.1 4.0)
          result (.process resampler factor buf 0 (alength buf) false out 0 estimated)]
      (Arrays/copyOf out (.outputSamplesGenerated result)))))

(defn- target-event [^floats buf]
  (doto (AudioEvent. (TarsosDSPAudioFormat. 16000.0 16 1 true false))
    (.setFloatBuffer buf)))

(defn- source-converter [^AudioFormat source-format downstream]
  (let [source-rate (.getSampleRate source-format)
        source-channels (.getChannels source-format)]
    (reify AudioProcessor
      (process [_ event]
        (let [buf (.getFloatBuffer ^AudioEvent event)
              mono (if (== 1 source-channels) buf (downmix buf source-channels))
              resampled (resample-to-target mono source-rate)
              new-event (target-event resampled)]
          (doseq [p downstream]
            (.process ^AudioProcessor p new-event))
          true))
      (processingFinished [_]
        (doseq [p downstream]
          (.processingFinished ^AudioProcessor p))))))

(defn- open-mixer-line [^Mixer mixer]
  (some (fn [^AudioFormat fmt]
          (when (.isLineSupported mixer (DataLine$Info. TargetDataLine fmt))
            (try
              (let [^TargetDataLine line (.getLine mixer (DataLine$Info. TargetDataLine fmt))]
                (.open line fmt)
                (.start line)
                (println "capture format:" (.getSampleRate fmt) "Hz" (.getChannels fmt) "ch")
                [line fmt])
              (catch LineUnavailableException _
                nil))))
        capture-formats))

(defn dispatcher-for [send-fn {:keys [mixer speaker]}]
  (let [detector (SilenceDetector. (silence-threshold-db) true)
        utterance (utterance-processor detector (fn [pcm] (send-fn speaker pcm)))
        dispatcher (if-let [[^TargetDataLine line ^AudioFormat fmt] (when mixer (open-mixer-line mixer))]
                     (let [d (AudioDispatcher. (JVMAudioInputStream. (AudioInputStream. line)) 1024 0)]
                       (.addAudioProcessor d (source-converter fmt [detector utterance]))
                       d)
                     (let [d (AudioDispatcherFactory/fromDefaultMicrophone 16000 1024 0)]
                       (.addAudioProcessor d detector)
                       (.addAudioProcessor d utterance)
                       d))]
    (doto (Thread. ^Runnable dispatcher) (.start))
    dispatcher))

(defn start-audio-capture!
  ([send-fn] (start-audio-capture! send-fn [{:mixer nil :speaker :candidate}]))
  ([send-fn devices]
   (mapv #(dispatcher-for send-fn %) devices)))

(defn stop-audio-capture! [dispatchers]
  (doseq [d dispatchers]
    (.stop ^AudioDispatcher d)))
