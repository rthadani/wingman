(ns wingman.frontend.agent
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter File)))

(defn format-context [{:keys [role topic history]}]
  (let [text (with-out-str
               (println "Role:" (or role ""))
               (println "Topic:" (or topic ""))
               (when (seq history)
                 (println "Transcript:")
                 (doseq [{:keys [speaker text]} history]
                   (println (str speaker ": " text)))))]
    text))

(defn- emit-lines! [line-ch carry delta]
  (let [s (str @carry delta)]
    (if (str/includes? s "\n")
      (let [parts (str/split s #"\n" -1)]
        (doseq [l (butlast parts)]
          (async/>!! line-ch l))
        (reset! carry (last parts)))
      (reset! carry s))))

(defn start-rpc-session!
  [{:keys [provider model name context-dir pi-bin]}]
  (let [skill-dirs ["system-design-interview" "engineering-pilot"]
        skill-flags (mapcat (fn [d]
                              (let [p (io/file context-dir "skills" d "SKILL.md")]
                                (when (.exists p)
                                  ["--skill" (str context-dir "/skills/" d)])))
                            skill-dirs)
        args (cond-> [(or pi-bin (System/getenv "PI_BIN") "pi") "--mode" "rpc"]
               provider (into ["--provider" provider])
               model (into ["--model" model])
               name (into ["--name" name])
               true (into ["--no-skills" "--no-prompt-templates" "--tools" "bash"])
               true (into skill-flags))
        pb (ProcessBuilder. args)]
    (.directory pb (File. ^String context-dir))
    (.redirectErrorStream pb true)
    (let [^Process proc (.start pb)
          out-ch (async/chan 1024)
          closed (async/promise-chan)
          reader (Thread.
                  (fn []
                    (try
                      (with-open [rdr (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
                        (loop [line (.readLine rdr)]
                          (when line
                            (when-let [ev (try (json/parse-string line true) (catch Exception _ nil))]
                              (async/>!! out-ch ev))
                            (recur (.readLine rdr)))))
                      (catch Exception _))
                    (async/close! out-ch)
                    (async/put! closed true)))]
      (.start reader)
      {:proc proc
       :in (OutputStreamWriter. (.getOutputStream proc))
       :out-ch out-ch
       :closed closed})))

(defn send-prompt! [{:keys [in]} message]
  (let [^java.io.Writer w in]
    (.write w (str (json/generate-string {:id (str (random-uuid)) :type "prompt" :message message}) "\n"))
    (.flush w)))

(defn run-turn!
  [{:keys [out-ch] :as conn} prompt line-ch]
  (send-prompt! conn prompt)
  (let [sb (StringBuilder.)
        carry (atom "")]
    (loop []
      (let [ev (async/<!! out-ch)]
        (if (nil? ev)
          (do (when (seq @carry) (async/>!! line-ch @carry))
              {:text (str sb)})
          (let [t (:type ev)]
            (when (= "message_update" t)
              (let [e (:assistantMessageEvent ev)]
                (when (= "text_delta" (:type e))
                  (let [d (str (:delta e))]
                    (.append sb d)
                    (emit-lines! line-ch carry d)))))
            (if (= "agent_settled" t)
              (do (when (seq @carry) (async/>!! line-ch @carry))
                  {:text (str sb)})
              (recur))))))))

(defn close-session! [{:keys [proc in]}]
  (try (.close ^java.io.Writer in) (catch Exception _))
  (try (.destroy proc) (catch Exception _)))
