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

(defn build-rpc-args
  [{:keys [provider model name context-dir pi-bin append-system-prompt home]}]
  (let [skill-dirs ["system-design-interview" "engineering-pilot"]
        skill-flags (mapcat (fn [d]
                              (let [p (io/file context-dir "skills" d "SKILL.md")]
                                (when (.exists p)
                                  ["--skill" (str context-dir "/skills/" d)])))
                            skill-dirs)
        extension-path (io/file context-dir "extensions" "web-search.ts")
        home (or home (System/getenv "HOME") (System/getProperty "user.home"))
        provider-extensions (when home
                              [(io/file home ".pi" "agent" "npm" "node_modules" "pi-claude-bridge" "src" "index.ts")
                               (io/file home ".pi" "agent" "git" "github.com" "rthadani" "pi-failover" "extensions" "failover.ts")])]
    (cond-> [(or pi-bin (System/getenv "PI_BIN") "pi") "--mode" "rpc"]
      true (into ["--no-extensions" "--no-context-files"])
      provider (into ["--provider" provider])
      model (into ["--model" model])
      name (into ["--name" name])
      append-system-prompt (into ["--append-system-prompt" append-system-prompt])
      true (into ["--no-skills" "--no-prompt-templates" "--no-mcp" "--tools" "interview_web_search"])
      (seq provider-extensions) (into (mapcat (fn [^File f]
                                                (when (.exists f)
                                                  ["-e" (str f)]))
                                              provider-extensions))
      (.exists extension-path) (into ["--extension" (str extension-path)])
      true (into skill-flags))))

(defn start-rpc-session!
  [opts]
  (let [args (build-rpc-args opts)
        pb (ProcessBuilder. args)
        context-dir (:context-dir opts)]
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
                            (if-let [ev (try (json/parse-string line true) (catch Exception _ nil))]
                              (async/>!! out-ch ev)
                              (binding [*out* *err*]
                                (println "pi:" line)))
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
      (let [[ev ch] (async/alts!! [out-ch (async/timeout 90000)])]
        (cond
          (and (nil? ev) (not (identical? ch out-ch)))
          (do (when (seq @carry) (async/>!! line-ch @carry))
              {:text (str sb) :timeout? true})

          (nil? ev)
          (do (when (seq @carry) (async/>!! line-ch @carry))
              {:text (str sb) :closed? true})

          :else
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
