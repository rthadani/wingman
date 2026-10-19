(ns wingman.frontend.handlers
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [wingman.frontend.capture :as capture]
            [wingman.frontend.capture-control :as capture-control]
            [wingman.frontend.pipelines :as pipelines]
            [wingman.frontend.renderer :as renderer]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [org.httpkit.server :as http-kit]))

(def ^:private new-session-js
  "(function(){var b=document.querySelector('button');if(!b){return;}b.addEventListener('click',function(){fetch('/api/session',{method:'POST'}).then(function(r){return r.json();}).then(function(d){location.href='/session/'+d.id;});});})();")

(def ^:private mute-toggle-js
  "(function(){var b=document.getElementById('mute-toggle');if(!b){return;}var muted=false;b.addEventListener('click',function(){muted=!muted;fetch(muted?'/api/capture/mute':'/api/capture/unmute',{method:'POST'});b.textContent=muted?'Unmute':'Mute';b.classList.toggle('bg-red-600',muted);b.classList.toggle('bg-slate-700',!muted);});})();")

(def ^:private pause-toggle-js
  "(function(){function setup(kind,paused){var b=document.getElementById('pause-'+kind+'-toggle');if(!b){return;}function paint(){b.textContent=(paused?'Resume ':'Pause ')+kind;b.classList.toggle('bg-amber-600',paused);b.classList.toggle('bg-slate-700',!paused);}b.addEventListener('click',function(){paused=!paused;fetch(paused?'/api/pause/'+kind:'/api/resume/'+kind,{method:'POST'});paint();});paint();}fetch('/api/pause/status').then(function(r){return r.json();}).then(function(s){setup('voice',!!s.voice);setup('text',!!s.text);}).catch(function(){setup('voice',false);setup('text',false);});})();")

(defn- app-html []
  (slurp (io/resource "public/app.html")))

(defn- inject-scripts [html scripts]
  (str/replace html "</body>" (str scripts "</body>")))

(defn- known-session?
  [sys id]
  (and (string? id)
       (re-matches #"[a-zA-Z0-9-]+" id)
       (.exists (io/file (get-in sys [:config :sessions-dir]) (str id ".edn")))))

(defn home-handler [_req]
  {:status 200
   :headers {"Content-Type" "text/html"
             "Cache-Control" "no-store, no-cache, must-revalidate"}
   :body (-> (app-html)
             (str/replace "hx-ext=\"sse\" sse-connect=\"/events\"" "")
             (str/replace "SESSION_ID" "NONE")
             (inject-scripts (str "<script>" new-session-js "</script>" "<script>" mute-toggle-js "</script>" "<script>" pause-toggle-js "</script>")))})

(defn session-create-handler [{:keys [wingman.frontend/sys]}]
  (let [ev (session/create-session (get-in sys [:config :sessions-dir]))
        id (:id ev)]
    (capture-control/start! (get-in sys [:config :capture-url]) id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:id id})}))

(defn capture-stop-handler [{:keys [wingman.frontend/sys]}]
  (capture-control/stop! (get-in sys [:config :capture-url]))
  {:status 202 :body "stopped"})

(defn capture-mute-handler [{:keys [wingman.frontend/sys]}]
  (capture-control/mute! (get-in sys [:config :capture-url]))
  {:status 202 :body "muted"})

(defn capture-unmute-handler [{:keys [wingman.frontend/sys]}]
  (capture-control/unmute! (get-in sys [:config :capture-url]))
  {:status 202 :body "unmuted"})

(defn- set-paused! [sys kind paused]
  (case kind
    "voice" (do (swap! (:paused sys) assoc :voice paused)
                {:status 202 :body (str (if paused "paused" "resumed") " voice")})
    "text" (do (swap! (:paused sys) assoc :text paused)
               {:status 202 :body (str (if paused "paused" "resumed") " text")})
    {:status 400 :body "unknown kind"}))

(defn pause-handler [{:keys [wingman.frontend/sys] :as req}]
  (set-paused! sys (get-in req [:path-params :kind]) true))

(defn resume-handler [{:keys [wingman.frontend/sys] :as req}]
  (set-paused! sys (get-in req [:path-params :kind]) false))

(defn pause-status-handler [{:keys [wingman.frontend/sys]}]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string @(:paused sys))})

(defn sessions-handler [{:keys [wingman.frontend/sys]}]
  (let [ids (session/list-sessions (get-in sys [:config :sessions-dir]))
        sessions (mapv (fn [id] {:id id :title id}) ids)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str (renderer/session-list-fragment sessions))}))

(defn session-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [events (session/load-session (get-in sys [:config :sessions-dir]) id)
            history (apply str (keep renderer/history-fragment events))]
        (capture-control/start! (get-in sys [:config :capture-url]) id)
        {:status 200
         :headers {"Content-Type" "text/html"
                   "Cache-Control" "no-store, no-cache, must-revalidate"}
         :body (-> (app-html)
                   (str/replace "sse-connect=\"/events\"" (str "sse-connect=\"/api/session/" id "/sse\""))
                   (str/replace "/api/session/SESSION_ID/chat" (str "/api/session/" id "/chat"))
                   (str/replace "sse-swap=\"transcript\" hx-swap=\"none\"></div>" (str "sse-swap=\"transcript\" hx-swap=\"none\">" history "</div>"))
                   (str/replace "sse-swap=\"assistant\" hx-swap=\"beforeend\"></div>" (str "sse-swap=\"assistant\" hx-swap=\"beforeend\">" history "</div>"))
                   (inject-scripts (str "<script>" new-session-js "</script>" "<script>" mute-toggle-js "</script>" "<script>" pause-toggle-js "</script>")))}))))

(defn sse-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [{:keys [body]} (http-kit/as-channel
                            req
                            {:on-receive (fn [_ch _msg])
                             :on-close   (fn [ch _status]
                                           (sse/unregister-client (:sse-registry sys) id ch))
                             :init       (fn [ch]
                                           (sse/register-client (:sse-registry sys) id ch)
                                           (sse/stream-headers! ch))})]
        {:status 200
         :headers {"Content-Type" "text/event-stream"
                   "Cache-Control" "no-cache"}
         :body body}))))

(defn audio-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [ch (pipelines/ensure-audio-pipeline! sys id)
            ^java.io.InputStream body (:body req)]
        (when body
          (async/offer! ch {:speaker :candidate :bytes (.readAllBytes body)}))
        {:status 202 :body "accepted"}))))

(defn- parse-form-message [body-str]
  (when-let [v (->> (str/split body-str #"&")
                    (map #(str/split % #"=" 2))
                    (some (fn [[k v]] (when (= "message" k) v))))]
    (java.net.URLDecoder/decode ^String v "UTF-8")))

(defn chat-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [ct (get-in req [:headers "content-type"])
            form? (and ct (str/starts-with? ct "application/x-www-form-urlencoded"))
            msg (try
                  (some-> (:body req)
                          slurp
                          (#(if form? (parse-form-message %) (-> % (json/parse-string true) :message))))
                  (catch Exception _ nil))]
        (if (not (string? msg))
          {:status 400 :body "expected message"}
          (let [ch (pipelines/ensure-chat-pipeline! sys id)]
            (async/offer! ch {:source :chat :text msg})
            {:status 202 :body "accepted"}))))))

(defn handle-ws-frame [sys session-id message]
  (if (bytes? message)
    (let [^bytes b message
          n (alength b)
          speaker (if (and (pos? n) (= 1 (aget b 0))) :interviewer :candidate)
          pcm (if (pos? n) (java.util.Arrays/copyOfRange b 1 n) b)]
      (async/offer! (pipelines/ensure-audio-pipeline! sys session-id)
                    {:speaker speaker :bytes (capture/pcm->wav pcm)}))
    (let [m (try (json/parse-string message true)
                 (catch Exception _ nil))]
      (when (and (map? m)
                 (= "clipboard.update" (:type m))
                 (string? (:payload m)))
        (async/offer! (pipelines/ensure-chat-pipeline! sys session-id)
                      {:source :clipboard :text (:payload m)})))))

(defn ws-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (cond
      (not (known-session? sys id)) {:status 404 :body "unknown session"}
      (not (:websocket? req)) {:status 400 :body "websocket required"}
      :else (http-kit/as-channel req
                                 {:on-receive (fn [_ch msg]
                                                (handle-ws-frame sys id msg))}))))
