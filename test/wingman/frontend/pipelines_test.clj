(ns wingman.frontend.pipelines-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.loop :as agent-loop]
            [wingman.frontend.pipelines :as pipelines]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [wingman.frontend.system :as system]
            [wingman.frontend.whisper :as whisper]
            [org.httpkit.server :as http-kit]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "interview-pipelines"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(defn- seed-session! [dir id]
  (spit (io/file dir (str id ".edn"))
        (prn-str {:type :session-created :id id})))

(defn- poll-for
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (async/<!! (async/timeout 25))
            (recur))))))

(defn- transcript-events [dir id]
  (filter #(= :transcript (:type %)) (session/load-session dir id)))

(defn- response-events [dir id]
  (filter #(= :response (:type %)) (session/load-session dir id)))

(defn- next-frame [client timeout-ms]
  (async/alt!! client ([f] f) (async/timeout timeout-ms) ([_] nil)))

(deftest audio-pipeline-transcribes-and-broadcasts-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-a")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [client (Object.)
                frames (atom [])]
            (with-redefs [http-kit/send! (fn [ch msg & _] (when (identical? ch client) (swap! frames conj msg)))
                          http-kit/close (fn [_ch] nil)]
              (sse/register-client (:sse-registry sys) "sess-a" client)
              (with-redefs [whisper/whisper-available? (fn [& _] true)
                            whisper/transcribe-pcm (fn [_] {:text "hello world today" :segments []})]
                (let [ch (pipelines/ensure-audio-pipeline! sys "sess-a")]
                  (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "audio-bytes")})))
                (is (pos? (count (poll-for 3000 #(seq (transcript-events dir "sess-a"))))))
                (let [ev (first (transcript-events dir "sess-a"))]
                  (is (= "hello world today" (:text ev)))
                  (is (= :candidate (:speaker ev)))
                  (is (= 1 (:id ev))))
                (let [frame (first (poll-for 2000 #(seq @frames)))]
                  (is (str/includes? (str frame) "event: transcript"))
                  (is (str/includes? (str frame) "hello world today"))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest audio-pipeline-tags-interviewer-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-i")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "interviewer says hello" :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-i")]
              (is (async/offer! ch {:speaker :interviewer :bytes (.getBytes "x")})))
            (is (pos? (count (poll-for 3000 #(seq (transcript-events dir "sess-i"))))))
            (is (= :interviewer (:speaker (first (transcript-events dir "sess-i"))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest audio-pipeline-whisper-unavailable-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-b")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [whisper/whisper-available? (fn [& _] false)]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-b")]
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "audio-bytes")})))
            (async/<!! (async/timeout 300))
            (is (empty? (transcript-events dir "sess-b"))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-streams-and-persists-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-c")
      (session/append-event dir "sess-c" {:type :transcript :speaker :candidate :text "earlier question" :id 1 :ts "t0"})
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [client (Object.)
                frames (atom [])
                prompts (atom [])]
            (with-redefs [http-kit/send! (fn [ch msg & _] (when (identical? ch client) (swap! frames conj msg)))
                          http-kit/close (fn [_ch] nil)]
              (sse/register-client (:sse-registry sys) "sess-c" client)
              (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                            agent-loop/close-session! (fn [_] nil)
                            agent-loop/run-turn!
                            (fn [_session prompt line-ch]
                              (swap! prompts conj prompt)
                              (async/put! line-ch "line one")
                              (async/put! line-ch "line two")
                              {:text "line one\nline two" :skip? false})]
                (let [ch (pipelines/ensure-chat-pipeline! sys "sess-c")]
                  (is (async/offer! ch "a question")))
                (is (pos? (count (poll-for 3000 #(seq (response-events dir "sess-c"))))))
                (let [ev (first (response-events dir "sess-c"))]
                  (is (= "line one\nline two" (:text ev))))
                (is (= 1 (count @prompts)))
                (is (str/includes? (first @prompts) "earlier question"))
                (is (str/includes? (first @prompts) "a question"))
                (is (poll-for 3000 #(<= 4 (count @frames))))
                (let [[f1 f2 f3 f4] @frames]
                  (is (str/includes? (str f1) "event: transcript"))
                  (is (str/includes? (str f1) "a question"))
                  (is (str/includes? (str f2) "line one"))
                  (is (str/includes? (str f3) "line two"))
                  (is (str/includes? (str f4) "event: assistant"))
                  (is (str/includes? (str f4) "line one&#10;line two"))
                  (is (str/includes? (str f4) "assistant-raw"))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-routes-clipboard-as-interviewer-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-clipboard")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                        agent-loop/close-session! (fn [_] nil)
                        agent-loop/run-turn! (fn [_session _prompt _line-ch] {:text "ok" :skip? true})]
            (let [ch (pipelines/ensure-chat-pipeline! sys "sess-clipboard")]
              (is (async/offer! ch {:source :clipboard :text "copied question"})))
            (async/<!! (async/timeout 300))
            (let [ev (first (transcript-events dir "sess-clipboard"))]
              (is (= "copied question" (:text ev)))
              (is (= :interviewer (:speaker ev)))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-skip-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-d")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                        agent-loop/close-session! (fn [_] nil)
                        agent-loop/run-turn! (fn [_session _prompt _line-ch] {:text "already covered" :skip? true})]
            (let [ch (pipelines/ensure-chat-pipeline! sys "sess-d")]
              (is (async/offer! ch "a question")))
            (async/<!! (async/timeout 300))
            (is (empty? (response-events dir "sess-d"))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-audio-pre-appended-runs-turn-without-duplicating-transcript-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-audio-pre")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                        agent-loop/close-session! (fn [_] nil)
                        agent-loop/run-turn! (fn [_session _prompt _line-ch] {:text "agent reply" :skip? false})]
            (let [ch (pipelines/ensure-chat-pipeline! sys "sess-audio-pre")]
              (is (async/offer! ch {:source :audio :speaker :candidate :text "hello there" :pre-appended? true})))
            (is (pos? (count (poll-for 3000 #(seq (response-events dir "sess-audio-pre"))))))
            (is (= "agent reply" (:text (first (response-events dir "sess-audio-pre")))))
            (is (empty? (transcript-events dir "sess-audio-pre"))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest meaningful-speech-detects-noise-test
  (is (false? ((var-get #'pipelines/meaningful-speech?) "[BLANK_AUDIO]")))
  (is (false? ((var-get #'pipelines/meaningful-speech?) "[silence]")))
  (is (false? ((var-get #'pipelines/meaningful-speech?) "(wind blowing) (wind blowing) [BLANK_AUDIO]")))
  (is (false? ((var-get #'pipelines/meaningful-speech?) "...")))
  (is (false? ((var-get #'pipelines/meaningful-speech?) "  ")))
  (is (false? ((var-get #'pipelines/meaningful-speech?) "hi")))
  (is (true? ((var-get #'pipelines/meaningful-speech?) "hi [silence] hello world")))
  (is (true? ((var-get #'pipelines/meaningful-speech?) "This is a real sentence."))))

(deftest audio-pipeline-filters-noise-markers-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-noise")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "[BLANK_AUDIO]" :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-noise")]
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "noise1")}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-noise")))))
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "(wind blowing) (wind blowing) [BLANK_AUDIO]" :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-noise")]
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "noise2")}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-noise")))))
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "..." :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-noise")]
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "noise3")}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-noise")))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest audio-pipeline-debounces-second-turn-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-debounce")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [calls (atom 0)]
            (with-redefs [whisper/whisper-available? (fn [& _] true)
                          whisper/transcribe-pcm (fn [_] {:text "hello world again" :segments []})
                          agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                          agent-loop/close-session! (fn [_] nil)
                          agent-loop/run-turn! (fn [_session _prompt _line-ch]
                                                 (swap! calls inc)
                                                 {:text "ok" :skip? false})]
              (let [ch (pipelines/ensure-audio-pipeline! sys "sess-debounce")]
                (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "first")}))
                (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "second")})))
              (async/<!! (async/timeout 800))
              (is (= 2 (count (transcript-events dir "sess-debounce"))))
              (is (= 1 @calls))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-survives-throwing-turn-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-e")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [calls (atom 0)]
            (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                          agent-loop/close-session! (fn [_] nil)
                          agent-loop/run-turn!
                          (fn [_session _prompt _line-ch]
                            (if (= 1 (swap! calls inc))
                              (throw (RuntimeException. "boom"))
                              {:text "recovered" :skip? false}))]
              (let [ch (pipelines/ensure-chat-pipeline! sys "sess-e")]
                (is (async/offer! ch "first"))
                (is (async/offer! ch "second")))
              (is (pos? (count (poll-for 5000 #(seq (response-events dir "sess-e"))))))
              (is (= "recovered" (:text (first (response-events dir "sess-e")))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest ensure-pipeline-idempotent-test
  (let [dir (temp-dir)]
    (try
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [ch1 (pipelines/ensure-audio-pipeline! sys "sess-f")
                ch2 (pipelines/ensure-audio-pipeline! sys "sess-f")]
            (is (identical? ch1 ch2)))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest stop-system-closes-pipelines-test
  (let [dir (temp-dir)]
    (try
      (let [sys (system/build-system {:sessions-dir dir})
            ch (pipelines/ensure-audio-pipeline! sys "sess-g")
            cch (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                              agent-loop/close-session! (fn [_] nil)]
                  (pipelines/ensure-chat-pipeline! sys "sess-g"))]
        (system/stop-system sys)
        (is (false? (async/offer! ch "x")))
        (is (false? (async/offer! cch "x")))
        (is (empty? @(:audio-pipelines sys)))
        (is (empty? @(:chat-pipelines sys))))
      (finally
        (delete-recursively! dir)))))

(deftest audio-pipeline-drops-while-voice-paused-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-h")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "fixed text here" :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-h")]
              (swap! (:paused sys) assoc :voice true)
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "one")}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-h")))
              (swap! (:paused sys) assoc :voice false)
              (is (async/offer! ch {:speaker :candidate :bytes (.getBytes "two")}))
              (is (pos? (count (poll-for 3000 #(seq (transcript-events dir "sess-h"))))))
              (is (= "fixed text here" (:text (first (transcript-events dir "sess-h")))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest chat-pipeline-drops-while-text-paused-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-i")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                        agent-loop/close-session! (fn [_] nil)
                        agent-loop/run-turn! (fn [_session _prompt _line-ch] {:text "x" :skip? false})]
            (let [ch (pipelines/ensure-chat-pipeline! sys "sess-i")]
              (swap! (:paused sys) assoc :text true)
              (is (async/offer! ch "paused question"))
              (async/<!! (async/timeout 300))
              (is (empty? (response-events dir "sess-i")))
              (swap! (:paused sys) assoc :text false)
              (is (async/offer! ch "live question"))
              (is (pos? (count (poll-for 3000 #(seq (response-events dir "sess-i"))))))
              (is (= "x" (:text (first (response-events dir "sess-i")))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))
