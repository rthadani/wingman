(ns wingman.capture.server
  (:require [wingman.capture.audio :as audio]
            [wingman.capture.clipboard :as clipboard]
            [wingman.capture.transport :as transport]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.client :as client]
            [reitit.ring :as ring]
            [org.httpkit.server :as http-kit]))

(defn default-agent-url []
  (or (System/getenv "AGENT_URL") "http://localhost:7777"))

(defn default-capture-port []
  (or (some-> (System/getenv "CAPTURE_PORT") parse-long) 7778))

(defn- derive-ws-url [agent-url session-id]
  (let [base (-> agent-url
                 (str/replace-first #"^http://" "ws://")
                 (str/replace-first #"^https://" "wss://"))]
    (str base "/ws/session/" session-id)))

(defn create-session! [agent-url]
  (let [url (str agent-url "/api/session")
        {:keys [status body]} @(client/post url {:headers {"Accept" "application/json"}})]
    (when (not= 200 status)
      (throw (ex-info "failed to create session" {:status status :body body})))
    (:id (json/parse-string body true))))

(defn current-status [state]
  (select-keys @state [:status :session-id]))

(defn- resolve-devices [entries]
  (if (seq entries)
    (let [normalized (mapv #(if (string? %) {:name % :speaker nil} %) entries)
          assigned (loop [remaining normalized
                          assigned-candidate? false
                          acc []]
                     (if (empty? remaining)
                       acc
                       (let [{:keys [name speaker]} (first remaining)
                             [speaker assigned-candidate?'] (if (some? speaker)
                                                              [speaker assigned-candidate?]
                                                              (if assigned-candidate?
                                                                [:interviewer true]
                                                                [:candidate true]))]
                         (recur (rest remaining) assigned-candidate?' (conj acc {:name name :speaker speaker})))))
          resolved (keep (fn [{:keys [name speaker]}]
                           (when-let [m (audio/find-mixer name)]
                             {:mixer m :speaker speaker}))
                         assigned)]
      (if (seq resolved) resolved [{:mixer nil :speaker :candidate}]))
    [{:mixer nil :speaker :candidate}]))

(defn start-capture! [state agent-url session-id]
  (swap! state
         (fn [s]
           (if (= :capturing (:status s))
             (assoc s :session-id session-id :frontend-url agent-url)
             (let [ws-atom (atom nil)
                   stop-ch (async/chan)
                   client (-> (java.net.http.HttpClient/newBuilder)
                              (.connectTimeout (java.time.Duration/ofSeconds 10))
                              .build)
                   ws-url (derive-ws-url agent-url session-id)
                   names (or (:devices s) [])
                   _ (doseq [n names :when (nil? (audio/find-mixer (:name n)))]
                       (binding [*out* *err*]
                         (println "audio device not found:" (:name n))))
                   devices (resolve-devices names)
                   _ (transport/keep-connected! ws-atom ws-url client stop-ch)
                   sender (transport/start-sender! ws-atom)
                   dispatchers (audio/start-audio-capture!
                                (fn [speaker pcm]
                                  (when-not (:muted? @state)
                                    (async/offer! sender [:audio pcm speaker])))
                                devices)]
               (try
                 (clipboard/start-clipboard-watch!
                  (fn [content]
                    (when-not (:muted? @state)
                      (async/offer! sender [:clipboard content]))))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "clipboard unavailable:" (.getMessage e)))))
               (assoc s
                      :status :capturing
                      :muted? false
                      :session-id session-id
                      :frontend-url agent-url
                      :ws-atom ws-atom
                      :stop-ch stop-ch
                      :client client
                      :sender sender
                      :dispatchers dispatchers))))))

(defn stop-capture! [state]
  (let [snapshot @state]
    (when (= :capturing (:status snapshot))
      (when-let [dispatchers (:dispatchers snapshot)]
        (audio/stop-audio-capture! dispatchers))
      (clipboard/stop-clipboard-watch!)
      (when-let [ws-atom (:ws-atom snapshot)]
        (when-let [ws (:ws @ws-atom)]
          (try (.abort ws) (catch Exception _))))
      (when-let [stop-ch (:stop-ch snapshot)]
        (async/close! stop-ch))
      (when-let [sender (:sender snapshot)]
        (async/close! sender)))
    (swap! state assoc
           :status :idle
           :muted? false
           :session-id nil
           :frontend-url nil
           :ws-atom nil
           :stop-ch nil
           :client nil
           :sender nil
           :dispatchers nil)))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn- slurp-body [req]
  (when-let [body (:body req)]
    (if (string? body) body (slurp (io/reader body)))))

(defn start [state req]
  (let [body (some-> (slurp-body req) (json/parse-string true))
        agent-url (or (:agent-url body) (default-agent-url))
        session-id (or (:session-id body) (create-session! agent-url))
        status (current-status state)]
    (when (and (= :capturing (:status status))
               (not= session-id (:session-id status)))
      (stop-capture! state))
    (start-capture! state agent-url session-id)
    (json-response {:session-id session-id :status (:status (current-status state))})))

(defn stop [state _req]
  (stop-capture! state)
  (json-response {:status (:status (current-status state))}))

(defn status [state _req]
  (let [s (current-status state)]
    (json-response {:status (:status s) :session-id (:session-id s) :muted? (boolean (:muted? @state))})))

(defn mute [state _req]
  (swap! state assoc :muted? true)
  (json-response {:muted? true}))

(defn unmute [state _req]
  (swap! state assoc :muted? false)
  (json-response {:muted? false}))

(defn start-server! [state port]
  (let [rh (ring/ring-handler
            (ring/router [["/start" {:post {:handler (partial start state)}}]
                          ["/stop" {:post {:handler (partial stop state)}}]
                          ["/mute" {:post {:handler (partial mute state)}}]
                          ["/unmute" {:post {:handler (partial unmute state)}}]
                          ["/status" {:get {:handler (partial status state)}}]])
            (ring/create-default-handler))]
    (http-kit/run-server rh {:port port})))

(defn stop-server! [server]
  (when server
    (server :timeout 100)))
