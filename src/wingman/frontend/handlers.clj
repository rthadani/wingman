(ns wingman.frontend.handlers
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.middleware.multipart-params :as mp]
            [ring.middleware.multipart-params.byte-array :as ba]
            [wingman.frontend.capture-control :as capture-control]
            [wingman.frontend.pipelines :as pipelines]
            [wingman.frontend.renderer :as renderer]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [wingman.frontend.uploads :as uploads]
            [wingman.frontend.whisper :as whisper]
            [org.httpkit.server :as http-kit]))

(def ^:private mute-toggle-js
  "(function(){var b=document.getElementById('mute-toggle');if(!b){return;}var muted=false;b.addEventListener('click',function(){muted=!muted;fetch(muted?'/api/capture/mute':'/api/capture/unmute',{method:'POST'});b.textContent=muted?'Unmute':'Mute';b.classList.toggle('bg-red-600',muted);b.classList.toggle('bg-slate-700',!muted);});})();")

(def ^:private pause-toggle-js
  "(function(){function setup(kind,paused){var b=document.getElementById('pause-'+kind+'-toggle');if(!b){return;}function paint(){b.textContent=(paused?'Resume ':'Pause ')+kind;b.classList.toggle('bg-amber-600',paused);b.classList.toggle('bg-slate-700',!paused);}b.addEventListener('click',function(){paused=!paused;fetch(paused?'/api/pause/'+kind:'/api/resume/'+kind,{method:'POST'});paint();});paint();}fetch('/api/pause/status').then(function(r){return r.json();}).then(function(s){setup('voice',!!s.voice);setup('text',!!s.text);}).catch(function(){setup('voice',false);setup('text',false);});})();")

(defn- clear-js
  [id]
  (str "(function(){var b=document.getElementById('clear-transcript');if(!b){return;}b.addEventListener('click',function(){if(confirm('Clear the transcript for this session?')){fetch('/api/session/" id "/clear',{method:'POST'});}});})();"))

(defn- edit-context-js
  []
  "(function(){var b=document.getElementById('edit-context');var p=document.getElementById('context-editor');if(!b||!p){return;}b.addEventListener('click',function(){p.classList.toggle('hidden');});var c=document.getElementById('cancel-context');if(c){c.addEventListener('click',function(){p.classList.add('hidden');});}})();")

(defn- app-html []
  (slurp (io/resource "public/app.html")))

(defn- new-html []
  (slurp (io/resource "public/new.html")))

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
             (str/replace "<!--CONTEXT_EDITOR-->" "")
             (inject-scripts (str "<script>" mute-toggle-js "</script>" "<script>" pause-toggle-js "</script>")))})

(defn- normalize-attachments
  "Normalises multipart file parts to a seq of {:filename :bytes} maps.
   Empty file inputs (blank filename and zero bytes) are dropped.
   The filename is sanitised to basename-only display metadata."
  [parts]
  (let [parts (if (map? parts) [parts] parts)
        parts (filter map? parts)
        parts (remove #(and (str/blank? (:filename %))
                            (zero? (count (:bytes %))))
                      parts)]
    (mapv #(-> (select-keys % [:bytes])
               (assoc :filename (uploads/safe-original-name %)))
          parts)))

(defn- validate-create-session
  [params attachments]
  (let [company (some-> (get params "company") str str/trim)]
    (cond
      (str/blank? company)
      {:error "company is required"}

      (not (uploads/within-limits? attachments))
      {:error "upload limits exceeded (max 10 files, 256 KB each, 1 MB total)"}

      (not (every? uploads/allowed-upload? attachments))
      {:error "only .md files are allowed"}

      (not (every? uploads/valid-utf8? attachments))
      {:error "files must be valid UTF-8 without NUL bytes"}

      :else
      {:ok {:company company
            :role (get params "role")
            :topic (get params "topic")
            :notes (get params "notes")
            :attachments attachments}})))

(defn session-create-handler* [{:keys [wingman.frontend/sys] :as req}]
  (let [params (:params req)
        attachments (normalize-attachments (get params "attachments"))
        validation (validate-create-session params attachments)]
    (if-let [err (:error validation)]
      {:status 400 :body err}
      (let [{:keys [company role topic notes attachments]} (:ok validation)
            sessions-dir (get-in sys [:config :sessions-dir])
            ev (session/create-session sessions-dir
                                       {:company company
                                        :role role
                                        :topic topic
                                        :notes notes}
                                       attachments)
            id (:id ev)]
        (capture-control/start! (get-in sys [:config :capture-url]) id)
        (reset! (:active-session sys) id)
        {:status 303
         :headers {"Location" (str "/session/" id)}
         :body ""}))))

(def session-create-handler
  (mp/wrap-multipart-params
   session-create-handler*
   {:store (ba/byte-array-store)}))

(defn new-session-handler [_req]
  {:status 200
   :headers {"Content-Type" "text/html"
             "Cache-Control" "no-store, no-cache, must-revalidate"}
   :body (new-html)})

(defn session-delete-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (do
        (session/delete-session! (get-in sys [:config :sessions-dir]) id)
        (capture-control/stop! (get-in sys [:config :capture-url]))
        (reset! (:active-session sys) nil)
        (when-let [entry (get @(:chat-pipelines sys) id)]
          (async/close! (:ch entry))
          (swap! (:chat-pipelines sys) dissoc id))
        (when-let [entry (get @(:audio-pipelines sys) id)]
          (async/close! (:ch entry))
          (swap! (:audio-pipelines sys) dissoc id))
        {:status 200
         :body ""}))))

(defn session-clear-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (try
        (binding [*out* *err*]
          (println "clear requested for session" id))
        (pipelines/clear-session! sys id)
        {:status 200 :body ""}
        (catch Exception e
          (binding [*out* *err*]
            (println "clear failed for session" id ":" (.getMessage e)))
          {:status 500 :body (.getMessage e)})))))

(defn- trim-or-nil [s]
  (let [t (some-> s str str/trim)]
    (when-not (str/blank? t) t)))

(defn- validate-context-update [params]
  (let [role (some-> (get params "role") str str/trim)
        topic (some-> (get params "topic") str str/trim)
        notes (some-> (get params "notes") str str/trim)]
    (cond
      (> (count role) 500)
      {:error "role must be 500 characters or fewer"}

      (> (count topic) 500)
      {:error "topic must be 500 characters or fewer"}

      (> (count notes) 4000)
      {:error "notes must be 4000 characters or fewer"}

      :else
      {:ok {:role (trim-or-nil role)
            :topic (trim-or-nil topic)
            :notes (trim-or-nil notes)}})))

(defn session-context-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [validation (validate-context-update (:params req))]
        (if-let [err (:error validation)]
          {:status 400 :body err}
          (do
            (pipelines/update-context! sys id (:ok validation))
            {:status 303
             :headers {"Location" (str "/session/" id)}
             :body ""}))))))

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
  (let [dir (get-in sys [:config :sessions-dir])
        ids (session/list-sessions dir)
        sessions (mapv (fn [id]
                         (let [ctx (session/session-context dir id)]
                           {:id id
                            :title (or (some-> (:company ctx) str str/trim not-empty) id)}))
                       ids)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str (renderer/session-list-fragment sessions @(:active-session sys)))}))

(defn session-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [dir (get-in sys [:config :sessions-dir])
            events (session/load-session dir id)
            transcript-history (apply str (keep renderer/history-fragment (filter #(= :transcript (:type %)) events)))
            assistant-history (apply str (keep renderer/history-fragment (filter #(= :response (:type %)) events)))
            ctx (session/session-context dir id)]
        (reset! (:active-session sys) id)
        (capture-control/start! (get-in sys [:config :capture-url]) id)
        {:status 200
         :headers {"Content-Type" "text/html"
                   "Cache-Control" "no-store, no-cache, must-revalidate"}
         :body (-> (app-html)
                   (str/replace "<!--CONTEXT_HEADER-->" (str (renderer/session-header-fragment ctx)))
                   (str/replace "<!--CONTEXT_EDITOR-->" (str (renderer/context-editor-fragment id ctx)))
                   (str/replace "sse-connect=\"/events\"" (str "sse-connect=\"/api/session/" id "/sse\""))
                   (str/replace "/api/session/SESSION_ID/chat" (str "/api/session/" id "/chat"))
                   (str/replace "sse-swap=\"transcript\" hx-swap=\"none\"></div>" (str "sse-swap=\"transcript\" hx-swap=\"none\">" transcript-history "</div>"))
                   (str/replace "sse-swap=\"assistant\" hx-swap=\"beforeend\"></div>" (str "sse-swap=\"assistant\" hx-swap=\"beforeend\">" assistant-history "</div>"))
                   (inject-scripts (str "<script>" mute-toggle-js "</script>"
                                        "<script>" pause-toggle-js "</script>"
                                        "<script>" (clear-js id) "</script>"
                                        "<script>" (edit-context-js) "</script>")))}))))
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

(defn chat-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (if-not (known-session? sys id)
      {:status 404 :body "unknown session"}
      (let [msg (or (get-in req [:params "message"])
                    (try
                      (some-> (:body req) slurp (json/parse-string true) :message)
                      (catch Exception _ nil)))]
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
                    {:speaker speaker :bytes pcm}))
    (let [m (try (json/parse-string message true)
                 (catch Exception _ nil))]
      (when (and (map? m)
                 (= "clipboard.update" (:type m))
                 (string? (:payload m)))
        (swap! (:stats sys) assoc :last-clipboard (System/currentTimeMillis))
        (async/offer! (pipelines/ensure-chat-pipeline! sys session-id)
                      {:source :clipboard :text (:payload m)})))))

(defn status-handler [{:keys [wingman.frontend/sys]}]
  (let [stats @(:stats sys)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string
            {:session @(:active-session sys)
             :capture (dissoc (:capture stats) :muted?)
             :whisper (whisper/whisper-available? (get-in sys [:config :whisper-model]))
             :last_clipboard (:last-clipboard stats)
             :last_audio (:last-audio stats)
             :last_turn (:last-turn stats)
             :turn_state (:turn-state stats)
             :now (System/currentTimeMillis)})}))

(defn ws-handler [{:keys [wingman.frontend/sys] :as req}]
  (let [id (get-in req [:path-params :id])]
    (cond
      (not (known-session? sys id)) {:status 404 :body "unknown session"}
      (not (:websocket? req)) {:status 400 :body "websocket required"}
      :else (http-kit/as-channel req
                                 {:on-receive (fn [_ch msg]
                                                (handle-ws-frame sys id msg))}))))
