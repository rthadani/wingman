(ns wingman.frontend.agent-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wingman.frontend.agent :as agent]))

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
