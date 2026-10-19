(ns wingman.frontend.compaction
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wingman.frontend.session :as session])
  (:import [java.nio.file Files Paths StandardCopyOption]))

(def compact-threshold 200)

(defn should-compact?
  [events]
  (> (count events) compact-threshold))

(defn- summarize-run
  [type-kind events]
  {:type :summary
   :of type-kind
   :count (count events)
   :text (->> events
              (keep :text)
              (str/join " "))})

(defn- compact-events
  [events]
  (loop [remaining events
         acc []]
    (if (empty? remaining)
      acc
      (let [t (:type (first remaining))
            run (->> remaining
                     (take-while #(= t (:type %)))
                     vec)
            rest' (drop (count run) remaining)]
        (recur rest'
               (if (#{:transcript :response} t)
                 (conj acc (summarize-run t run))
                 (into acc run)))))))

(defn compact-session!
  [sessions-dir id]
  (let [events (session/load-session sessions-dir id)]
    (when (seq events)
      (let [compacted (compact-events events)
            path (.toFile (Paths/get sessions-dir (into-array String [(str id ".edn")])))]
        (when (not= compacted events)
          (let [tmp (java.io.File/createTempFile (.getName path) ".tmp")]
            (try
              (with-open [w (io/writer tmp)]
                (doseq [e compacted]
                  (.write w (prn-str e))))
              (Files/move (.toPath tmp) (.toPath path)
                          (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
              (catch Throwable e
                (.delete tmp)
                (throw e)))))
        compacted))))
