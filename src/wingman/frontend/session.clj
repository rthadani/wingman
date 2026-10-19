(ns wingman.frontend.session
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.util UUID]))

(def sessions-dir "data/sessions")

(defn- session-path
  [sessions-dir id]
  (.toPath (io/file sessions-dir (str id ".edn"))))

(defn- ensure-dir!
  [sessions-dir]
  (.mkdirs (io/file sessions-dir)))

(defn- write-events!
  [sessions-dir id events]
  (ensure-dir! sessions-dir)
  (with-open [w (io/writer (.toFile (session-path sessions-dir id)))]
    (doseq [e events]
      (.write w (prn-str e)))))

(defn- read-events
  [sessions-dir id]
  (let [f (io/file sessions-dir (str id ".edn"))]
    (if (.exists f)
      (with-open [r (io/reader f)]
        (doall (map edn/read-string (line-seq r))))
      nil)))

(defn create-session
  ([]
   (create-session (str (UUID/randomUUID))))
  ([sessions-dir]
   (create-session sessions-dir {}))
  ([sessions-dir meta]
   (let [id (str (UUID/randomUUID))
         event {:type :session-created
                :id id
                :meta meta
                :created-at (str (java.time.Instant/now))}]
     (write-events! sessions-dir id [event])
     event)))

(defn load-session
  [sessions-dir id]
  (read-events sessions-dir id))

(defn append-event
  [sessions-dir id event]
  (write-events! sessions-dir id (conj (vec (read-events sessions-dir id)) event)))

(defn list-sessions
  [sessions-dir]
  (let [dir (io/file sessions-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
           (mapv #(.getName ^java.io.File %))
           (mapv #(subs % 0 (- (count %) 4)))
           sort)
      [])))
