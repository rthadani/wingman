(ns wingman.frontend.uploads
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.charset Charset CodingErrorAction]))

(def max-files 10)
(def max-file-bytes (* 256 1024))
(def max-total-bytes (* 1024 1024))

(defn allowed-upload?
  "Returns true when the uploaded filename has a .md extension (case-insensitive)."
  [{:keys [filename]}]
  (boolean (when filename
             (str/ends-with? (str/lower-case filename) ".md"))))

(defn valid-utf8?
  "Returns true when the bytes are valid UTF-8 and contain no NUL characters."
  [{:keys [bytes]}]
  (try
    (let [decoder (.. (Charset/forName "UTF-8")
                      newDecoder
                      (onMalformedInput CodingErrorAction/REPORT)
                      (onUnmappableCharacter CodingErrorAction/REPORT))
          s (.toString (.decode decoder (ByteBuffer/wrap ^bytes bytes)))]
      (not (str/includes? s "\u0000")))
    (catch Exception _ false)))

(defn within-limits?
  "Returns true when there are at most 10 files, each <= 256 KB, and total <= 1 MB."
  [parts]
  (and (<= (count parts) max-files)
       (every? #(<= (count (:bytes %)) max-file-bytes) parts)
       (<= (transduce (map #(count (:bytes %))) + 0 parts) max-total-bytes)))

(defn safe-original-name
  "Basename-only display name stripped to [A-Za-z0-9._-] and capped at 120 chars."
  [{:keys [filename]}]
  (when filename
    (let [base (last (str/split filename #"[\\\\/]"))
          cleaned (str/replace base #"[^A-Za-z0-9._-]" "")]
      (subs cleaned 0 (min 120 (count cleaned))))))
