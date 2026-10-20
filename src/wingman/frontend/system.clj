(ns wingman.frontend.system
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [org.httpkit.client :as http]
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
   :paused (atom {:voice false :text false})
   :stats (atom {:last-clipboard nil
                 :last-audio {}
                 :last-turn nil
                 :turn-state :idle
                 :capture {:status :unknown}})
   :capture-poller-stop (async/chan)})

(defn- start-capture-poller! [system]
  (let [stop-ch (:capture-poller-stop system)
        url (str (get-in system [:config :capture-url]) "/status")]
    (async/thread
      (loop []
        (let [timeout (async/timeout 2000)
              [_ ch] (async/alts!! [stop-ch timeout])]
          (when-not (= ch stop-ch)
            (try
              (let [{:keys [status body]} @(http/get url)]
                (when (= 200 status)
                  (swap! (:stats system) assoc :capture (json/parse-string body true))))
              (catch Exception _ nil))
            (recur)))))))

(defn start-system [system]
  (start-capture-poller! system)
  (assoc system :server
         (server/start! {:port (get-in system [:config :port]) :sys system})))

(defn stop-system [system]
  (async/close! (:capture-poller-stop system))
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
