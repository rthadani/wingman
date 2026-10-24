(ns wingman.capture.core
  (:require [wingman.capture.audio :as audio]
            [wingman.capture.server :as server]
            [clojure.core.async :as async]
            [clojure.string :as str])
  (:gen-class))

(defn- continuation-token? [token]
  (boolean (re-matches #"^\d+(=.*)?$" token)))

(defn env-device-names [env-val]
  (let [tokens (->> (str/split (or env-val "") #",")
                    (map str/trim)
                    (remove str/blank?))
        entries (reduce (fn [acc token]
                          (if (and (seq acc) (continuation-token? token))
                            (conj (pop acc) (str (peek acc) "," token))
                            (conj acc token)))
                        []
                        tokens)]
    (mapv (fn [entry]
            (if-let [[_ name speaker] (re-matches #"(?i)^(.+?)=(candidate|interviewer)$" entry)]
              {:name name :speaker (keyword (str/lower-case speaker))}
              {:name entry :speaker nil}))
          entries)))

(defn parse-selection-line [devices line]
  (if (str/blank? line)
    []
    (->> (str/split line #",")
         (map str/trim)
         (keep #(when (re-matches #"\d+" %) (parse-long %)))
         (keep #(nth devices (dec %) nil))
         (mapv #(hash-map :name (:name %) :speaker nil)))))

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
      (println "capturing devices:" (str/join ", " (map :name devices))))
    (swap! state assoc :server (server/start-server! state port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (server/stop-capture! state)
                                 (server/stop-server! (:server @state)))))
    (println "capture control server on port" port)
    (async/<!! (async/chan))))
