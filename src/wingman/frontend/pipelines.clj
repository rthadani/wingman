(ns wingman.frontend.pipelines
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [wingman.frontend.agent :as agent]
            [wingman.frontend.capture :as capture]
            [wingman.frontend.loop :as agent-loop]
            [wingman.frontend.renderer :as renderer]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [wingman.frontend.whisper :as whisper])
  (:import (java.time Instant)))

(def ^:private history-window 20)

(defn- paused? [sys kind] (get @(:paused sys) kind false))

(defn- session-lock
  [sys session-id]
  (let [store (:session-store sys)]
    (or (get @store session-id)
        (let [o (Object.)]
          (swap! store (fn [m] (if (contains? m session-id) m (assoc m session-id o))))
          (get @store session-id)))))

(defn- ensure-pipeline!
  [pipelines-atom session-id buffer-size start-consumer!]
  (or (some-> (get @pipelines-atom session-id) :ch)
      (let [ch (async/chan (async/dropping-buffer buffer-size))
            [_ m] (swap-vals! pipelines-atom
                              (fn [m] (if (contains? m session-id) m (assoc m session-id {:ch ch}))))
            entry (get m session-id)]
        (if (identical? ch (:ch entry))
          (do (start-consumer! ch) ch)
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
        (when-not (paused? sys :voice)
          (try
            (let [cfg (:config sys)]
              (when (whisper/whisper-available? (:whisper-model cfg))
                (let [text (str/replace (:text (capture/process-audio-chunk bytes {:model (:whisper-model cfg)})) "\n" " ")]
                  (when-not (= "" text)
                    (let [ev (append-transcript! sys session-id text speaker)]
                      (sse/broadcast (:sse-registry sys) session-id "transcript"
                                     (str (renderer/transcript-fragment
                                           {:id (:id ev) :speaker speaker :text text :timestamp (:ts ev)}))))))))
            (catch Exception e
              (binding [*out* *err*]
                (println "audio chunk failed:" (.getMessage e))))))
        (recur)))))

(defn ensure-audio-pipeline!
  [sys session-id]
  (ensure-pipeline! (:audio-pipelines sys) session-id 32
                    #(start-audio-consumer! sys session-id %)))

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
  [sys session-id]
  (agent/format-context {:history (session-history sys session-id)}))

(defn- run-chat-turn
  [sys session session-id msg]
  (let [{:keys [text source]} (if (map? msg) msg {:text msg :source :chat})
        speaker (if (= :clipboard source) :interviewer :candidate)
        ev (append-transcript! sys session-id text speaker)
        line-ch (async/chan (async/dropping-buffer 256))
        watcher (async/go-loop []
                  (when-let [line (async/<! line-ch)]
                    (sse/broadcast (:sse-registry sys) session-id "assistant"
                                   (str (h/html [:span {:class "block text-sm text-slate-700 assistant-stream"} line])))
                    (recur)))
        result (try
                (sse/broadcast (:sse-registry sys) session-id "transcript"
                               (str (renderer/transcript-fragment
                                     {:id (:id ev) :speaker speaker :text text :timestamp (:ts ev)})))
                (agent-loop/run-turn! session (chat-prompt sys session-id) line-ch)
                (finally
                  (async/close! line-ch)
                  (async/<!! watcher)))]
    (when-not (:skip? result)
      (let [response-text (str (:text result))]
        (append-response! sys session-id response-text)
        (sse/broadcast (:sse-registry sys) session-id "assistant"
                       (str (renderer/response-fragment response-text true)))))))

(defn- start-chat-consumer!
  [sys session-id ch]
  (async/thread
    (let [cfg (:config sys)
          session (try
                    (agent-loop/start-session!
                     {:tools-dir (:tools-dir cfg)
                      :provider (:provider cfg)
                      :model (:model cfg)
                      :name session-id
                      :pi-bin (:pi-bin cfg)})
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "rpc session start failed:" (.getMessage e)))
                      nil))]
      (try
        (when session
          (loop []
            (when-let [msg (async/<!! ch)]
              (when-not (paused? sys :text)
                (try
                  (run-chat-turn sys session session-id msg)
                  (catch Exception e
                    (binding [*out* *err*]
                      (println "agent turn failed:" (.getMessage e))))))
              (recur))))
        (finally
          (agent-loop/close-session! session))))))

(defn ensure-chat-pipeline!
  [sys session-id]
  (ensure-pipeline! (:chat-pipelines sys) session-id 8
                    #(start-chat-consumer! sys session-id %)))
