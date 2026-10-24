(ns wingman.frontend.audio-filter
  "Pure audio/text filters for dropping whisper hallucinations."
  (:require [clojure.string :as str]))

(defn- pcm16-sample
  "Decode the signed 16-bit little-endian sample at index i."
  ^long [^bytes pcm ^long i]
  (let [lo (bit-and (aget pcm (* 2 i)) 0xFF)
        hi (aget pcm (inc (* 2 i)))
        raw (bit-or (bit-shift-left hi 8) lo)]
    (if (>= raw 32768) (- raw 65536) raw)))

(defn dbfs
  "Compute AC RMS loudness in dBFS for little-endian signed PCM16 bytes.
   The sample mean (DC offset) is removed before measuring, so a constant
   offset carried by the capture device does not read as loudness.
   Returns Double/NEGATIVE_INFINITY for empty input or a chunk with no AC
   energy (e.g. all-zero or a flat DC block)."
  ^double [^bytes pcm]
  (let [len (alength pcm)
        samples (quot len 2)]
    (if (zero? samples)
      Double/NEGATIVE_INFINITY
      (let [mean (loop [i 0
                        sum 0.0]
                   (if (>= i samples)
                     (/ sum samples)
                     (recur (inc i) (+ sum (double (pcm16-sample pcm i))))))
            energy (loop [i 0
                          sum 0.0]
                     (if (>= i samples)
                       sum
                       (let [dev (- (double (pcm16-sample pcm i)) mean)]
                         (recur (inc i) (+ sum (* dev dev))))))]
        (if (zero? energy)
          Double/NEGATIVE_INFINITY
          (let [rms-sq (/ energy samples)]
            (* 10.0 (Math/log10 (/ rms-sq (* 32768.0 32768.0))))))))))

(defn loud-enough?
  "True when the PCM16 chunk is louder than min-db. Empty chunks are dropped."
  [^bytes pcm ^double min-db]
  (> (dbfs pcm) min-db))

(def ^:private annotation-pattern
  "Whisper writes [..] and (..) annotations to describe non-speech sound, often on
   the same chunk as real speech."
  #"\[[^\]]*\]|\([^)]*\)")

(defn strip-annotations
  "Remove every [..] and (..) annotation, collapse the whitespace it leaves behind
   and trim: \"No, no. [BLANK_AUDIO]\" becomes \"No, no.\"."
  [text]
  (-> text
      (str/replace annotation-pattern " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- annotated?
  [text]
  (boolean (re-find annotation-pattern text)))

(def ^:private min-annotated-speech-words
  "An annotated chunk that leaves fewer words of speech than this is tag-only noise
   such as \"[BLANK_AUDIO]\" or \"(keyboard clicking) [BLANK_AUDIO]\"."
  4)

(defn- remove-punctuation
  "Lowercase and replace punctuation with spaces, keeping apostrophes."
  [text]
  (-> text
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\s']" " ")
      str/trim))

(defn- normalized-sentences
  [text]
  (->> (str/split text #"[.!?\r\n]+")
       (map (comp str/trim remove-punctuation))
       (remove str/blank?)))

(defn- normalized-words
  [text]
  (->> (str/split (remove-punctuation text) #"\s+")
       (remove str/blank?)))

(defn- periodic-repetition?
  "Detects a word sequence that is composed of repetitions of its first k words."
  [words]
  (let [n (count words)]
    (boolean
     (some (fn [k]
             (and (>= n (* 2 k))
                  (every? #(= (nth words %)
                              (nth words (rem % k)))
                          (range n))))
           (range 1 (inc 8))))))

(defn- sentence-repetition?
  [text]
  (boolean
   (some #(>= % 3)
         (vals (frequencies (normalized-sentences text))))))

(defn- low-unique-ratio?
  [words]
  (let [n (count words)]
    (and (>= n 12)
         (< (/ (double (count (distinct words))) n) 0.35))))

(def ^:private denylist
  #{"thank you for watching"
    "thanks for watching"
    "please subscribe"
    "please like and subscribe"})

(defn- denylist-match?
  [text]
  (contains? denylist (remove-punctuation text)))

(defn hallucinated-text?
  "Returns true when the transcription looks like a whisper hallucination.
   Annotations are stripped first, so real speech that carries a trailing tag such
   as [BLANK_AUDIO] is kept; the text rules then run on the stripped text. An
   annotated chunk that leaves fewer than min-annotated-speech-words words of
   speech is tag-only noise and is dropped."
  [text]
  (let [clean (strip-annotations text)
        words (normalized-words clean)]
    (or (and (annotated? text)
             (< (count words) min-annotated-speech-words))
        (periodic-repetition? words)
        (sentence-repetition? clean)
        (low-unique-ratio? words)
        (denylist-match? clean))))
