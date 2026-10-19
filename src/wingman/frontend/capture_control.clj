(ns wingman.frontend.capture-control
  (:require [cheshire.core :as json]
            [org.httpkit.client :as client]))

(defn- log-err [prefix resp-or-throwable]
  (let [msg (if (instance? Throwable resp-or-throwable)
              (.getMessage ^Throwable resp-or-throwable)
              (str "unexpected status " (:status resp-or-throwable)))]
    (binding [*out* *err*]
      (println prefix msg))))

(defn start! [capture-url session-id]
  (try
    (client/post (str capture-url "/start")
                 {:headers {"Content-Type" "application/json"}
                  :body (json/generate-string {:session-id session-id})}
                 (fn [resp]
                   (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                     (log-err "capture start failed:" resp))))
    (catch Exception e
      (log-err "capture start failed:" e))))

(defn stop! [capture-url]
  (try
    (client/post (str capture-url "/stop")
                 (fn [resp]
                   (when (or (instance? Throwable resp) (not= 200 (:status resp)))
                     (log-err "capture stop failed:" resp))))
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
