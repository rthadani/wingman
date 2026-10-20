(ns wingman.frontend.server
  (:require [wingman.frontend.handlers :as handlers]
            [org.httpkit.server :as http-kit]
            [reitit.ring :as ring]))

(defn- sys-middleware [sys]
  (fn [handler]
    (fn [req]
      (handler (assoc req :wingman.frontend/sys sys)))))

(defn- routes []
  [["/" {:get {:handler handlers/home-handler}}]
   ["/api/session" {:post {:handler handlers/session-create-handler}}]
   ["/api/capture/stop" {:post {:handler handlers/capture-stop-handler}}]
   ["/api/capture/mute" {:post {:handler handlers/capture-mute-handler}}]
   ["/api/capture/unmute" {:post {:handler handlers/capture-unmute-handler}}]
   ["/api/pause/status" {:conflicting true :get {:handler handlers/pause-status-handler}}]
   ["/api/status" {:get {:handler handlers/status-handler}}]
   ["/api/pause/:kind" {:conflicting true :post {:handler handlers/pause-handler}}]
   ["/api/resume/:kind" {:post {:handler handlers/resume-handler}}]
   ["/api/session/:id" {:delete {:handler handlers/session-delete-handler}}]
   ["/api/session/:id/audio" {:post {:handler handlers/audio-handler}}]
   ["/api/session/:id/chat" {:post {:handler handlers/chat-handler}}]
   ["/api/session/:id/sse" {:get {:handler handlers/sse-handler}}]
   ["/sessions" {:get {:handler handlers/sessions-handler}}]
   ["/session/:id" {:get {:handler handlers/session-handler}}]
   ["/ws/session/:id" {:get {:handler handlers/ws-handler}}]])

(defn- app [sys]
  (let [rh (ring/ring-handler
            (ring/router (routes) {:data {:middleware [(sys-middleware sys)]}})
            (ring/create-default-handler))]
    (fn
      ([req] (rh req))
      ([req respond raise]
       (let [res (rh req)]
         (if (fn? res)
           (res respond raise)
           (respond res)))))))

(defn start! [{:keys [port sys]}]
  (try
    (http-kit/run-server (app sys) {:port port :ring-async? true :max-ws 8388608})
    (catch java.net.BindException e
      (throw (ex-info "port in use" {:port port} e)))))

(defn stop! [server]
  (when server
    (server :timeout 100)))
