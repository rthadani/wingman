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

(defn- mixer-infos []
  (AudioSystem/getMixerInfo))

(defn- mixer-for [mi]
  (AudioSystem/getMixer mi))

(defn- supports-target-line? [mi]
  (.isLineSupported (mixer-for mi) (DataLine$Info. TargetDataLine target-format)))

(defn find-mixer [name-or-desc]
  (let [t (str/lower-case (str/trim (or name-or-desc "")))]
    (when-not (str/blank? t)
      (or (some (fn [mi]
                  (when (and (supports-target-line? mi)
                             (or (= t (str/lower-case (.getName mi)))
                                 (= t (str/lower-case (.getDescription mi)))))
                    (mixer-for mi)))
                (mixer-infos))
          (some (fn [mi]
                  (when (and (supports-target-line? mi)
                             (or (str/includes? (str/lower-case (.getName mi)) t)
                                 (str/includes? (str/lower-case (.getDescription mi)) t)))
                    (mixer-for mi)))
                (mixer-infos))))))

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
                (binding [*out* *err*]
                  (println "capture format:" (.getSampleRate fmt) "Hz" (.getChannels fmt) "ch"))
                [line fmt])
              (catch LineUnavailableException _
                nil))))
        capture-formats))

(defn- pcm-rms [^bytes pcm]
  (let [n (alength pcm)
        samples (quot n 2)]
    (if (zero? samples)
      0
      (let [bb (doto (ByteBuffer/wrap pcm)
                 (.order ByteOrder/LITTLE_ENDIAN))
            sum (loop [i 0 s 0.0]
                  (if (< i samples)
                    (let [v (double (.getShort bb))]
                      (recur (inc i) (+ s (* v v))))
                    s))]
        (int (Math/sqrt (/ (double sum) (max 1 samples))))))))

(defn- logged-send-fn [speaker send-fn]
  (fn [pcm]
    (try
      (binding [*out* *err*]
        (println "audio-flush" speaker (quot (count pcm) 2) "samples rms" (pcm-rms pcm)))
      (catch Exception _))
    (send-fn speaker pcm)))

(defn dispatcher-for [send-fn {:keys [mixer speaker]}]
  (let [detector (SilenceDetector. (silence-threshold-db) true)
        utterance (utterance-processor detector (logged-send-fn speaker send-fn))
        [dispatcher line] (if-let [[^TargetDataLine line ^AudioFormat fmt] (when mixer (open-mixer-line mixer))]
                            [(let [d (AudioDispatcher. (JVMAudioInputStream. (AudioInputStream. line)) 1024 0)]
                               (.addAudioProcessor d (source-converter fmt [detector utterance]))
                               d)
                             line]
                            [(let [d (AudioDispatcherFactory/fromDefaultMicrophone 16000 1024 0)]
                               (.addAudioProcessor d detector)
                               (.addAudioProcessor d utterance)
                               d)
                             nil])]
    (doto (Thread. ^Runnable dispatcher) (.start))
    (binding [*out* *err*])
    (cond-> {:dispatcher dispatcher}
      line (assoc :line line))))

(defn start-audio-capture!
  ([send-fn] (start-audio-capture! send-fn [{:mixer nil :speaker :candidate}]))
  ([send-fn devices]
   (mapv #(dispatcher-for send-fn %) devices)))

(defn stop-audio-capture! [entries]
  (doseq [e entries]
    (.stop ^AudioDispatcher (:dispatcher e))
    (when-let [^TargetDataLine line (:line e)]
      (try
        (.stop line)
        (.close line)
        (catch Exception _)))))
