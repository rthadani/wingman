(ns wingman.frontend.pipelines
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [wingman.frontend.agent :as agent]
            [wingman.frontend.audio-filter :as audio-filter]
            [wingman.frontend.loop :as agent-loop]
            [wingman.frontend.renderer :as renderer]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [wingman.frontend.whisper :as whisper])
  (:import (java.time Instant)))

(def ^:private history-window 20)

(def ^:private last-audio-turn (atom {}))

(declare ensure-chat-pipeline!)

(defn- meaningful-speech?
  "Returns true when the transcription contains enough real speech to keep.
   Strips non-speech markers such as [BLANK_AUDIO] or (wind blowing)."
  [text]
  (let [clean (-> text
                  (str/replace #"\[[^\]]*\]" "")
                  (str/replace #"\([^)]*\)" "")
                  str/trim)]
    (and (>= (count (str/split clean #"\s+")) 3)
         (>= (count clean) 12))))

(defn- paused? [sys kind] (get @(:paused sys) kind false))

(defn- session-lock
  [sys session-id]
  (let [store (:session-store sys)]
    (or (get @store session-id)
        (let [o (Object.)]
          (swap! store (fn [m] (if (contains? m session-id) m (assoc m session-id o))))
          (get @store session-id)))))

(defn- ensure-pipeline!
  [pipelines-atom session-id buffer-size entry-from-ch start-consumer!]
  (or (some-> (get @pipelines-atom session-id) :ch)
      (let [ch (async/chan (async/dropping-buffer buffer-size))
            [_ m] (swap-vals! pipelines-atom
                              (fn [m] (if (contains? m session-id) m (assoc m session-id (entry-from-ch ch)))))
            entry (get m session-id)]
        (if (identical? ch (:ch entry))
          (do (start-consumer! entry) ch)
          (do (async/close! ch) (:ch entry))))))

(defn- append-transcript!
  [sys session-id text speaker]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (session-lock sys session-id)
    (let [cfg (:config sys)
          events (session/load-session (:sessions-dir cfg) session-id)
          n (inc (count (filter #(= :transcript (:type %)) events)))
          ev {:type :transcript :speaker speaker :text text :ts (str (Instant/now)) :id n}]
      (session/append-event (:sessions-dir cfg) session-id ev)
      ev)))

(defn- start-audio-consumer!
  [sys session-id ch]
  (async/thread
    (loop []
      (when-let [{:keys [speaker bytes]} (async/<!! ch)]
        (swap! (:stats sys) assoc-in [:last-audio speaker] (System/currentTimeMillis))
        (let [level (audio-filter/dbfs bytes)]
          (binding [*out* *err*]
            (println "audio-chunk rx" speaker (count bytes) level))
          (when-not (paused? sys :voice)
            (try
              (let [cfg (:config sys)
                    min-db (:min-audio-dbfs cfg -50.0)]
                (if (audio-filter/loud-enough? bytes min-db)
                  (when (whisper/whisper-available? (:whisper-model cfg))
                    (let [t (audio-filter/strip-annotations
                             (str/replace (:text (whisper/transcribe-pcm bytes)) "\n" " "))]
                      (binding [*out* *err*]
                        (println "transcribed" speaker (count t) "chars"))
                      (cond
                        (not (meaningful-speech? t))
                        (binding [*out* *err*]
                          (println "audio-drop" speaker "no-speech" t))

                        (audio-filter/hallucinated-text? t)
                        (binding [*out* *err*]
                          (println "audio-drop" speaker "hallucination" t))

                        :else
                        (let [ev (append-transcript! sys session-id t speaker)]
                          (sse/broadcast (:sse-registry sys) session-id "transcript"
                                         (str (renderer/transcript-fragment
                                               {:id (:id ev) :speaker speaker :text t :timestamp (:ts ev)})))
                          (let [now (System/currentTimeMillis)
                                last (get @last-audio-turn session-id 0)]
                            (when (>= (- now last) 6000)
                              (swap! last-audio-turn assoc session-id now)
                              (async/offer! (ensure-chat-pipeline! sys session-id)
                                            {:source :audio :speaker speaker :text t :pre-appended? true})))))))
                  (binding [*out* *err*]
                    (println "audio-drop" speaker "quiet" level))))
              (catch Exception e
                (binding [*out* *err*]
                  (println "audio chunk failed:" (.getMessage e)))))))
        (recur)))))

(defn ensure-audio-pipeline!
  [sys session-id]
  (ensure-pipeline! (:audio-pipelines sys) session-id 32
                    (fn [ch] {:ch ch})
                    #(start-audio-consumer! sys session-id (:ch %))))

(defn- append-response!
  [sys session-id text]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (session-lock sys session-id)
    (session/append-event (get-in sys [:config :sessions-dir]) session-id
                          {:type :response :text text :ts (str (Instant/now))})))

(defn- session-history
  [sys session-id]
  (->> (session/load-session (get-in sys [:config :sessions-dir]) session-id)
       (filter #(#{:transcript :response} (:type %)))
       (take-last history-window)
       (mapv (fn [ev]
               {:speaker (case (:type ev)
                           :transcript (if (= :interviewer (:speaker ev)) "Interviewer" "Candidate")
                           :response "Assistant")
                :text (:text ev)}))))

(defn- chat-prompt
  [sys session-id ctx]
  (let [{:keys [company role topic]} ctx
        topic-str (str (or company "")
                       (when (and (seq company) (seq topic)) " — ")
                       (or topic ""))]
    (agent/format-context {:role (or role "")
                           :topic topic-str
                           :history (session-history sys session-id)})))

(defn- run-chat-turn
  [sys session session-id msg ctx]
  (swap! (:stats sys) assoc :turn-state :thinking)
  (try
    (let [{:keys [text source speaker pre-appended?]} (if (map? msg) msg {:text msg :source :chat})
          speaker (or speaker (if (= :clipboard source) :interviewer :candidate))
          ev (when-not pre-appended? (append-transcript! sys session-id text speaker))
          line-ch (async/chan (async/dropping-buffer 256))
          watcher (async/go-loop []
                    (when-let [line (async/<! line-ch)]
                      (sse/broadcast (:sse-registry sys) session-id "assistant"
                                     (str (h/html [:span {:class "block text-sm text-slate-700 assistant-stream"} line])))
                      (recur)))
          result (try
                  (when-not pre-appended?
                    (sse/broadcast (:sse-registry sys) session-id "transcript"
                                   (str (renderer/transcript-fragment
                                         {:id (:id ev) :speaker speaker :text text :timestamp (:ts ev)}))))
                  (agent-loop/run-turn! session (chat-prompt sys session-id ctx) line-ch)
                  (finally
                    (async/close! line-ch)
                    (async/<!! watcher)))]
      (when-not (or (:skip? result) (:closed? result))
        (let [response-text (str (:text result))]
          (append-response! sys session-id response-text)
          (sse/broadcast (:sse-registry sys) session-id "assistant"
                         (str (renderer/response-fragment response-text true)))))
      result)
    (finally
      (swap! (:stats sys) assoc :turn-state :idle :last-turn (System/currentTimeMillis)))))

(defn- start-chat-consumer!
  [sys session-id ch clear-ch]
  (async/thread
    (let [cfg (:config sys)
          sessions-dir (get-in sys [:config :sessions-dir])
          ctx (session/session-context sessions-dir session-id)
          ctx-md (session/context-markdown sessions-dir session-id ctx)
          start-session-or-nil (fn [md]
                                 (try
                                   (agent-loop/start-session!
                                    {:tools-dir (:tools-dir cfg)
                                     :provider (:provider cfg)
                                     :model (:model cfg)
                                     :name session-id
                                     :pi-bin (:pi-bin cfg)
                                     :context md})
                                   (catch Exception e
                                     (binding [*out* *err*]
                                       (println "rpc session start failed:" (.getMessage e)))
                                     nil)))
          session (atom (start-session-or-nil ctx-md))]
      (try
        (loop [ctx ctx ctx-md ctx-md]
          (let [[msg port] (async/alts!! [clear-ch ch] :priority true)]
            (cond
              (nil? msg) nil
              (identical? port clear-ch)
              (do
                (binding [*out* *err*]
                  (println "consumer clearing session" session-id))
                (agent-loop/close-session! @session)
                (reset! session nil)
                (if (= :clear msg)
                  (do
                    ;; drop chat messages queued before the clear
                    (loop [] (when (async/poll! ch) (recur)))
                    (locking (session-lock sys session-id)
                      (session/clear-transcript! sessions-dir session-id))
                    (sse/broadcast (:sse-registry sys) session-id "clear" "")
                    (recur ctx ctx-md))
                  ;; :context - refresh context without clearing transcript
                  (let [fresh-ctx (session/session-context sessions-dir session-id)
                        fresh-md (session/context-markdown sessions-dir session-id fresh-ctx)]
                    (recur fresh-ctx fresh-md))))
              :else
              (do
                (when-not (paused? sys :text)
                  (try
                    (when-not @session (reset! session (start-session-or-nil ctx-md)))
                    (when @session
                      (let [r (run-chat-turn sys @session session-id msg ctx)]
                        (when (or (:timeout? r) (:closed? r))
                          (agent-loop/close-session! @session)
                          (reset! session nil))))
                    (catch Exception e
                      (agent-loop/close-session! @session)
                      (reset! session nil)
                      (binding [*out* *err*]
                        (println "agent turn failed:" (.getMessage e))))))
                (recur ctx ctx-md)))))
        (finally
          (agent-loop/close-session! @session))))))

(defn update-context!
  "Updates the persisted session context and refreshes the assistant's memory
   from the next turn. The transcript is preserved."
  [sys session-id ctx-data]
  (locking (session-lock sys session-id)
    (session/update-session-context! (get-in sys [:config :sessions-dir]) session-id ctx-data))
  (if-let [entry (get @(:chat-pipelines sys) session-id)]
    (async/offer! (:clear entry) :context)
    (binding [*out* *err*]
      (println "context update for session" session-id "without pipeline"))))

(defn clear-session!
  "Resets a session in place: clears its transcript events, closes the active pi
   conversation so the next turn starts fresh, and broadcasts a clear event. A
   clear requested while a reply is in flight is queued on the pipeline's clear
   channel and applied once that reply finishes, discarding the chat messages
   that were queued before it."
  [sys session-id]
  (swap! last-audio-turn dissoc session-id)
  (swap! (:stats sys) assoc :turn-state :idle :last-turn nil)
  (if-let [entry (get @(:chat-pipelines sys) session-id)]
    (async/offer! (:clear entry) :clear)
    (locking (session-lock sys session-id)
      (binding [*out* *err*]
        (println "clearing session" session-id "without pipeline"))
      (session/clear-transcript! (get-in sys [:config :sessions-dir]) session-id)
      (sse/broadcast (:sse-registry sys) session-id "clear" ""))))

(defn ensure-chat-pipeline!
  [sys session-id]
  (ensure-pipeline! (:chat-pipelines sys) session-id 8
                    (fn [ch] {:ch ch :clear (async/chan (async/sliding-buffer 1))})
                    #(start-chat-consumer! sys session-id (:ch %) (:clear %))))
