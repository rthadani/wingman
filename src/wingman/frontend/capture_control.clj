(ns wingman.frontend.capture-control
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [wingman.capture.audio :as audio]
            [org.httpkit.client :as client]))

(defn- log-err [prefix resp-or-throwable]
  (let [msg (if (instance? Throwable resp-or-throwable)
              (.getMessage ^Throwable resp-or-throwable)
              (str "unexpected status " (:status resp-or-throwable)))]
    (binding [*out* *err*]
      (println prefix msg))))

(defn- parse-pactl-sinks [output]
  (loop [lines (str/split-lines output)
         current nil
         result []]
    (if (empty? lines)
      (cond-> result current (conj current))
      (let [line (first lines)]
        (cond
          (re-find #"^Sink #" line)
          (recur (rest lines) {} (cond-> result current (conj current)))

          (and current (re-find #"^\s+Name:" line))
          (recur (rest lines) (assoc current :name (str/trim (second (str/split line #":\s*" 2)))) result)

          (and current (re-find #"^\s+Description:" line))
          (recur (rest lines) (assoc current :description (str/trim (second (str/split line #":\s*" 2)))) result)

          :else
          (recur (rest lines) current result))))))

(defn list-sinks []
  (when-not (audio/mac?)
    (try
      (let [proc (-> (ProcessBuilder. ^java.util.List ["pactl" "list" "sinks"])
                     (.redirectErrorStream true)
                     .start)
            reader-future (future (slurp (.getInputStream proc)))]
        (if (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS)
          (keep (fn [{:keys [name description]}]
                  (let [lname (str/lower-case (or name ""))]
                    (when (and name
                               (not (str/includes? lname "monitor"))
                               (not (str/includes? lname "aloop"))
                               (not (str/includes? lname "loopback")))
                      {:name name :description (or description name)})))
                (parse-pactl-sinks @reader-future))
          (do (.destroy proc) nil)))
      (catch Exception _ []))))

(defn update-loopback! [sink-name]
  (when-not (audio/mac?)
    (let [env-file (str (System/getenv "HOME") "/.config/wingman/loopback.env")]
      (try
        (spit env-file (str "SOURCE=" sink-name ".monitor\n"))
        (future
          (.waitFor (.start (ProcessBuilder. ["systemctl" "--user" "restart" "wingman-pw-loopback"]))))
        (catch Exception e
          (binding [*out* *err*]
            (println "loopback update failed:" (.getMessage e))))))))

(defn start!
  ([capture-url session-id] (start! capture-url session-id nil))
  ([capture-url session-id {:keys [mic-device]}]
   (try
     (client/post (str capture-url "/start")
                  {:headers {"Content-Type" "application/json"}
                   :body (json/generate-string (cond-> {:session-id session-id}
                                                 mic-device (assoc :mic-device mic-device)))}
                  (fn [resp]
                    (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                      (log-err "capture start failed:" resp))))
     (catch Exception e
       (log-err "capture start failed:" e)))))

(defn stop! [capture-url]
  (try
    (client/post (str capture-url "/stop")
                 (fn [resp]
                   (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                     (log-err "capture stop failed:" resp))))
    (catch Exception e
      (log-err "capture stop failed:" e))))

(defn stop-sync! [capture-url]
  (try
    @(client/post (str capture-url "/stop") {})
    (catch Exception e
      (log-err "capture stop failed:" e))))

(defn mute! [capture-url]
  (try
    (client/post (str capture-url "/mute")
                 (fn [resp]
                   (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                     (log-err "capture mute failed:" resp))))
    (catch Exception e
      (log-err "capture mute failed:" e))))

(defn unmute! [capture-url]
  (try
    (client/post (str capture-url "/unmute")
                 (fn [resp]
                   (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                     (log-err "capture unmute failed:" resp))))
    (catch Exception e
      (log-err "capture unmute failed:" e))))

(defn status! [capture-url on-ok on-err]
  (try
    (client/get (str capture-url "/status")
                (fn [resp]
                  (if (or (instance? Throwable resp) (not= 200 (:status resp)))
                    (on-err resp)
                    (on-ok (json/parse-string (:body resp) true)))))
    (catch Exception e
      (on-err e))))
