(ns wingman.frontend.system
  (:require [clojure.core.async :as async]
            [org.httpkit.server :as http-kit]
            [wingman.frontend.server :as server]
            [wingman.frontend.sse :as sse]))

(defn build-system [cfg]
  {:config cfg
   :server nil
   :session-store (atom {})
   :active-session (atom nil)
   :sse-registry (sse/new-registry)
   :audio-pipelines (atom {})
   :chat-pipelines (atom {})
   :paused (atom {:voice false :text false})})

(defn start-system [system]
  (assoc system :server
         (server/start! {:port (get-in system [:config :port]) :sys system})))

(defn stop-system [system]
  (server/stop! (:server system))
  (doseq [chs (vals @(:sse-registry system))
          ch chs]
    (try (http-kit/close ch) (catch Exception _ nil)))
  (doseq [reg [:audio-pipelines :chat-pipelines]
          entry (vals @(get system reg))]
    (async/close! (:ch entry)))
  (reset! (:sse-registry system) {})
  (reset! (:audio-pipelines system) {})
  (reset! (:chat-pipelines system) {})
  (reset! (:session-store system) {})
  (reset! (:paused system) {:voice false :text false})
  (assoc system :server nil))
