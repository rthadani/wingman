(ns wingman.capture.core
  (:require [wingman.capture.audio :as audio]
            [wingman.capture.server :as server]
            [clojure.core.async :as async]
            [clojure.string :as str])
  (:gen-class))

(defn env-device-names [env-val]
  (->> (str/split (or env-val "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn parse-selection-line [devices line]
  (if (str/blank? line)
    []
    (->> (str/split line #",")
         (map str/trim)
         (keep #(when (re-matches #"\d+" %) (parse-long %)))
         (keep #(nth devices (dec %) nil))
         (map :name)
         vec)))

(defn select-devices
  ([] (select-devices (audio/list-devices) (System/console) (audio/env "CAPTURE_DEVICES")))
  ([devices console env-val]
   (cond
     console (do
               (println "Available audio input devices:")
               (doseq [[i d] (map-indexed vector devices)]
                 (println (str "  " (inc i) ") " (:name d)
                              (when-not (str/blank? (:description d))
                                (str " — " (:description d))))))
               (print "Select devices (comma-separated numbers; first = you/mic, rest = interviewer; blank = default mic): ")
               (flush)
               (parse-selection-line devices (.readLine console)))
     (not (str/blank? env-val)) (env-device-names env-val)
     :else [])))

(defn -main [& _args]
  (let [port (server/default-capture-port)
        devices (select-devices)
        state (atom {:status :idle :session-id nil :muted? false :server nil :devices devices})]
    (when (seq devices)
      (println "capturing devices:" (str/join ", " devices)))
    (swap! state assoc :server (server/start-server! state port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (server/stop-capture! state)
                                 (server/stop-server! (:server @state)))))
    (println "capture control server on port" port)
    (async/<!! (async/chan))))
