(ns wingman.frontend.tools-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.tools :as tools])
  (:import (java.nio.file Files)))

(defn tmp-dir []
  (str (Files/createTempDirectory "tools-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn run-script [script args dir]
  (apply shell/sh "bash" script (concat args [:dir dir])))

(deftest recall-script-test
  (let [log (io/file (tmp-dir) "history.log")]
    (spit log "2024-01-01 role: backend engineer\n2024-01-02 topic: system design\n")
    (let [{:keys [out exit]} (run-script "tools/recall_session_history" [(.getPath log) "topic"] ".")]
      (is (zero? exit))
      (is (str/includes? out "system design")))
    (let [{:keys [out]} (run-script "tools/recall_session_history" [(.getPath log)] ".")]
      (is (str/includes? out "backend engineer")))))

(deftest mark-answered-writes-marker-test
  (let [dir (tmp-dir)
        {:keys [exit]} (run-script (.getAbsolutePath (io/file "tools" "mark_topic_answered")) [] dir)]
    (is (zero? exit))
    (is (.exists (io/file dir ".skip-suggestion")))
    (is (tools/skip-marked? dir))))

(deftest stage-tools-test
  (let [context-dir (tools/stage-tools! {:tools-dir "tools"})]
    (is (every? #(.exists (io/file context-dir "tools" %)) tools/tool-names))
    (is (every? #(.canExecute (io/file context-dir "tools" %)) tools/tool-names))
    (is (.exists (io/file context-dir "skills" "system-design-interview" "SKILL.md")))
    (is (.exists (io/file context-dir "skills" "engineering-pilot" "SKILL.md")))
    (is (.exists (io/file context-dir "extensions" "web-search.ts")))
    (is (= (slurp (io/file "resources" "extensions" "web-search.ts"))
           (slurp (io/file context-dir "extensions" "web-search.ts"))))
    (is (.exists (io/file context-dir "AGENT.md")))
    (is (nil? (tools/cleanup-context-dir! context-dir nil)))
    (is (not (.exists (io/file context-dir))))))

(deftest skip-marked-and-cleanup-test
  (let [context-dir (tools/stage-tools! {:tools-dir "tools"})]
    (is (not (tools/skip-marked? context-dir)))
    (spit (io/file context-dir ".skip-suggestion") "")
    (is (tools/skip-marked? context-dir))
    (let [archive-dir (tmp-dir)
          archived (tools/cleanup-context-dir! context-dir {:archive-dir archive-dir})]
      (is (some? archived))
      (is (not (.exists (io/file context-dir)))))))

(deftest search-transcript-script-test
  (let [t (io/file (tmp-dir) "transcript.log")]
    (spit t "interviewer: what is a race condition?\ncandidate: when threads share state\n")
    (let [{:keys [out exit]} (run-script "tools/search_transcript" [(.getPath t) "race"] ".")]
      (is (zero? exit))
      (is (str/includes? out "race condition")))))
