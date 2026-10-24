(ns wingman.frontend.agent-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wingman.frontend.agent :as agent])
  (:import (java.nio.file Files)))

(deftest format-context-test
  (let [ctx {:role "Backend Engineer"
             :topic "Clojure concurrency"
             :history [{:speaker "Interviewer" :text "What is an atom?"}
                       {:speaker "Candidate" :text "A mutable reference with CAS semantics."}]}
        text (agent/format-context ctx)
        file (java.io.File/createTempFile "context" ".txt")]
    (try
      (spit file text)
      (let [written (slurp file)]
        (is (str/includes? written "Role: Backend Engineer"))
        (is (str/includes? written "Topic: Clojure concurrency"))
        (is (str/includes? written "Interviewer: What is an atom?"))
        (is (str/includes? written "Candidate: A mutable reference with CAS semantics.")))
      (finally (.delete file)))))

(deftest format-context-no-history-test
  (testing "format-context without history omits transcript section"
    (let [text (agent/format-context {:role "r" :topic "t"})]
      (is (str/includes? text "Role: r"))
      (is (str/includes? text "Topic: t"))
      (is (not (str/includes? text "Transcript:"))))))

(deftest run-turn-streams-text-deltas-test
  (let [out-ch (async/chan 8)
        line-ch (async/chan)
        conn {:out-ch out-ch}
        lines (atom [])
        watcher (async/go-loop []
                  (when-let [l (async/<! line-ch)]
                    (swap! lines conj l)
                    (recur)))]
    (with-redefs [agent/send-prompt! (fn [_ _] nil)]
      (async/put! out-ch {:type "message_update" :assistantMessageEvent {:type "text_delta" :delta "Hello "}})
      (async/put! out-ch {:type "message_update" :assistantMessageEvent {:type "text_delta" :delta "world\nline two"}})
      (async/put! out-ch {:type "agent_settled"})
      (let [res (agent/run-turn! conn "prompt" line-ch)]
        (is (= {:text "Hello world\nline two"} res))))
    (async/close! line-ch)
    (async/<!! watcher)
    (is (= ["Hello world" "line two"] @lines))))

(defn- tmp-dir []
  (str (Files/createTempDirectory "agent-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(deftest build-rpc-args-uses-interview-web-search-tool-and-no-mcp-test
  (let [dir (tmp-dir)]
    (try
      (let [args (agent/build-rpc-args {:context-dir dir})]
        (is (some #(= "--tools" %) args))
        (is (some #(= "interview_web_search" %) args))
        (is (some #(= "--no-mcp" %) args))
        (is (some #(= "--no-extensions" %) args))
        (is (some #(= "--no-context-files" %) args))
        (is (not (some #(= "bash" %) args)))
        (is (not (some #(= "--extension" %) args))))
      (finally
        (delete-recursively! dir)))))

(deftest build-rpc-args-includes-extension-when-file-exists-test
  (let [dir (tmp-dir)
        ext-file (io/file dir "extensions" "web-search.ts")]
    (try
      (.mkdirs (.getParentFile ext-file))
      (spit ext-file "// extension")
      (let [args (agent/build-rpc-args {:context-dir dir})]
        (is (some #(= "--extension" %) args))
        (is (some #(= (.getPath ext-file) %) args)))
      (finally
        (delete-recursively! dir)))))

(deftest build-rpc-args-includes-provider-extensions-when-files-exist-test
  (let [dir (tmp-dir)
        home (tmp-dir)
        ext-file (io/file dir "extensions" "web-search.ts")
        claude-bridge (io/file home ".pi" "agent" "npm" "node_modules" "pi-claude-bridge" "src" "index.ts")
        failover (io/file home ".pi" "agent" "git" "github.com" "rthadani" "pi-failover" "extensions" "failover.ts")]
    (try
      (.mkdirs (.getParentFile ext-file))
      (.mkdirs (.getParentFile claude-bridge))
      (.mkdirs (.getParentFile failover))
      (spit ext-file "// extension")
      (spit claude-bridge "// claude-bridge")
      (spit failover "// failover")
      (let [args (agent/build-rpc-args {:context-dir dir :home home})]
        (is (some #(= "-e" %) args))
        (is (some #(= (.getPath claude-bridge) %) args))
        (is (some #(= (.getPath failover) %) args))
        (is (< (.indexOf args (.getPath failover))
               (.indexOf args "--extension")
               (.indexOf args (.getPath ext-file)))))
      (finally
        (delete-recursively! dir)
        (delete-recursively! home)))))

(deftest build-rpc-args-includes-provider-model-name-and-append-system-prompt-test
  (let [dir (tmp-dir)
        prompt-file (java.io.File/createTempFile "prompt" ".md")]
    (try
      (let [args (agent/build-rpc-args {:context-dir dir
                                        :provider "openai"
                                        :model "gpt-4"
                                        :name "interview"
                                        :append-system-prompt (.getPath prompt-file)
                                        :pi-bin "/usr/local/bin/pi"})]
        (is (= "/usr/local/bin/pi" (first args)))
        (is (some #(= "--provider" %) args))
        (is (some #(= "openai" %) args))
        (is (some #(= "--model" %) args))
        (is (some #(= "gpt-4" %) args))
        (is (some #(= "--name" %) args))
        (is (some #(= "interview" %) args))
        (is (some #(= "--append-system-prompt" %) args))
        (is (some #(= (.getPath prompt-file) %) args)))
      (finally
        (.delete prompt-file)
        (delete-recursively! dir)))))

(deftest build-rpc-args-includes-skill-flags-when-skills-exist-test
  (let [dir (tmp-dir)]
    (try
      (doseq [skill ["system-design-interview" "engineering-pilot"]]
        (let [f (io/file dir "skills" skill "SKILL.md")]
          (.mkdirs (.getParentFile f))
          (spit f "// skill")))
      (let [args (agent/build-rpc-args {:context-dir dir})]
        (is (some #(= "--skill" %) args))
        (is (some #(= (str dir "/skills/system-design-interview") %) args))
        (is (some #(= (str dir "/skills/engineering-pilot") %) args)))
      (finally
        (delete-recursively! dir)))))
