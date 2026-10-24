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

(defn- loud-bytes []
  "A loud PCM16 chunk that passes the -50 dBFS energy gate."
  (byte-array [0xFF 0x7F 0x00 0x80 0xFF 0x7F 0x00 0x80]))

(defn- quiet-bytes []
  "Silence that should be dropped by the energy gate."
  (byte-array 64))

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
                  (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)})))
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
              (is (async/offer! ch {:speaker :interviewer :bytes (loud-bytes)})))
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
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)})))
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
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-noise")))))
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "(wind blowing) (wind blowing) [BLANK_AUDIO]" :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-noise")]
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-noise")))))
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "..." :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-noise")]
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
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
                (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
                (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)})))
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

(deftest chat-pipeline-closed-child-resets-and-starts-fresh-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-closed")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [started (atom 0)
                closed (atom [])
                run-count (atom 0)]
            (with-redefs [agent-loop/start-session! (fn [_opts]
                                                       (swap! started inc)
                                                       {:context-dir (str "closed-child-" @started) :conn {}})
                          agent-loop/close-session! (fn [session]
                                                       (swap! closed conj (:context-dir session)))
                          agent-loop/run-turn! (fn [_session _prompt _line-ch]
                                                  (swap! run-count inc)
                                                  (if (= 1 @run-count)
                                                    {:text "" :skip? false :closed? true}
                                                    {:text "recovered" :skip? false :closed? false}))]
              (let [ch (pipelines/ensure-chat-pipeline! sys "sess-closed")]
                (is (async/offer! ch "first"))
                (is (async/offer! ch "second")))
              (is (pos? (count (poll-for 5000 #(seq (response-events dir "sess-closed"))))))
              (is (= 1 (count (response-events dir "sess-closed"))))
              (is (= "recovered" (:text (first (response-events dir "sess-closed")))))
              (is (= 2 @run-count))
              (is (= 2 @started))
              (is (= ["closed-child-1"] (filter #(= "closed-child-1" %) @closed)))))
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
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
              (async/<!! (async/timeout 300))
              (is (empty? (transcript-events dir "sess-h")))
              (swap! (:paused sys) assoc :voice false)
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)}))
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

(deftest audio-pipeline-energy-gate-drops-quiet-chunk-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-quiet")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [calls (atom 0)]
            (with-redefs [whisper/whisper-available? (fn [& _] true)
                          whisper/transcribe-pcm (fn [_] (swap! calls inc) {:text "should not run" :segments []})]
              (let [ch (pipelines/ensure-audio-pipeline! sys "sess-quiet")]
                (is (async/offer! ch {:speaker :candidate :bytes (quiet-bytes)})))
              (async/<!! (async/timeout 300))
              (is (zero? @calls))
              (is (empty? (transcript-events dir "sess-quiet")))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest audio-pipeline-drops-hallucinated-text-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-ghost")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (with-redefs [whisper/whisper-available? (fn [& _] true)
                        whisper/transcribe-pcm (fn [_] {:text "Thank you for watching." :segments []})]
            (let [ch (pipelines/ensure-audio-pipeline! sys "sess-ghost")]
              (is (async/offer! ch {:speaker :candidate :bytes (loud-bytes)})))
            (async/<!! (async/timeout 300))
            (is (empty? (transcript-events dir "sess-ghost"))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest clear-transcript-consumer-closes-child-and-does-not-respawn-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-clear")
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [started (atom 0)
                closed (atom [])
                clears (atom 0)]
            ;; record which child was closed: other consumer threads from earlier
            ;; tests may also call close-session! on the shared var
            (with-redefs [agent-loop/start-session! (fn [_opts]
                                                       (swap! started inc)
                                                       {:context-dir "clear-test-child" :conn {}})
                          agent-loop/close-session! (fn [session]
                                                       (swap! closed conj (:context-dir session)))
                          agent-loop/run-turn! (fn [_session _prompt _line-ch]
                                                  (async/<!! (async/timeout 2000))
                                                  {:text "ok" :skip? true})
                          sse/broadcast (fn [_reg _sid evt _data]
                                          (when (= "clear" evt) (swap! clears inc)))]
              (let [ch (pipelines/ensure-chat-pipeline! sys "sess-clear")
                    clear-ch (:clear (get @(:chat-pipelines sys) "sess-clear"))]
                (async/offer! ch "first")
                (async/<!! (async/timeout 100))
                (async/offer! clear-ch :clear))
              ;; wait for the clear broadcast: it is sent after the transcript trim
              (is (true? (poll-for 5000 #(= 1 @clears))))
              (is (= 1 (count (filter #{"clear-test-child"} @closed))))
              (is (= 1 @started))
              (let [events (session/load-session dir "sess-clear")]
                (is (= 1 (count events)))
                (is (= :session-created (:type (first events)))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest clear-session-with-pipeline-offers-clear-message-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-clear-msg")
      (let [sys (system/build-system {:sessions-dir dir})
            ch (async/chan 1)
            clear-ch (async/chan (async/sliding-buffer 1))]
        (try
          (swap! (:chat-pipelines sys) assoc "sess-clear-msg" {:ch ch :clear clear-ch})
          (with-redefs [sse/broadcast (fn [_reg _sid _evt _data]
                                         (throw (ex-info "should not broadcast" {})))]
            (pipelines/clear-session! sys "sess-clear-msg")
            (is (= :clear (async/<!! clear-ch))))
          (finally
            (async/close! ch)
            (async/close! clear-ch)
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest clear-session-without-pipeline-trims-file-and-broadcasts-test
  (let [dir (temp-dir)]
    (try
      (seed-session! dir "sess-clear-nopipe")
      (session/append-event dir "sess-clear-nopipe" {:type :transcript :speaker :candidate :text "x" :id 1 :ts "t1"})
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [broadcasts (atom [])]
            (with-redefs [sse/broadcast (fn [_reg sid evt data]
                                           (swap! broadcasts conj [sid evt data]))]
              (pipelines/clear-session! sys "sess-clear-nopipe")
              (is (= [["sess-clear-nopipe" "clear" ""]] @broadcasts))
              (let [events (session/load-session dir "sess-clear-nopipe")]
                (is (= 1 (count events)))
                (is (= :session-created (:type (first events)))))
              (is (nil? (get @(var-get #'wingman.frontend.pipelines/last-audio-turn) "sess-clear-nopipe")))
              (is (= :idle (get @(:stats sys) :turn-state)))
              (is (nil? (get @(:stats sys) :last-turn)))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))

(deftest update-context-consumer-closes-child-and-keeps-transcript-test
  (let [dir (temp-dir)]
    (try
      (spit (io/file dir "sess-ctx.edn")
            (prn-str {:type :session-created
                      :id "sess-ctx"
                      :meta {:company "Acme" :role "Dev" :topic "Clojure" :notes "notes"}}))
      (session/append-event dir "sess-ctx" {:type :transcript :speaker :candidate :text "keep me" :id 1 :ts "t1"})
      (let [sys (system/build-system {:sessions-dir dir})]
        (try
          (let [started (atom 0)
                closed (atom [])
                run-count (atom 0)
                contexts (atom [])
                broadcasts (atom [])
                turn-started (promise)]
            (with-redefs [agent-loop/start-session! (fn [opts]
                                                      (swap! started inc)
                                                      (swap! contexts conj (:context opts))
                                                      {:context-dir (str "ctx-child-" @started) :conn {}})
                          agent-loop/close-session! (fn [session]
                                                      (swap! closed conj (:context-dir session)))
                          agent-loop/run-turn! (fn [_session _prompt _line-ch]
                                                 (swap! run-count inc)
                                                 (when-not (realized? turn-started)
                                                   (deliver turn-started true))
                                                 (async/<!! (async/timeout 600))
                                                 {:text (str "reply-" @run-count) :skip? false})
                          sse/broadcast (fn [_reg _sid evt _data]
                                          (when (= "clear" evt) (swap! broadcasts conj evt)))]
              (let [ch (pipelines/ensure-chat-pipeline! sys "sess-ctx")
                    clear-ch (:clear (get @(:chat-pipelines sys) "sess-ctx"))]
                (async/offer! ch "first")
                (is (deref turn-started 2000 false) "first turn should start")
                (session/update-session-context! dir "sess-ctx" {:role "Senior Dev" :topic "Java" :notes "updated"})
                (async/offer! clear-ch :context)
                (async/offer! ch "second")
                (is (true? (poll-for 5000 #(<= 2 @run-count))))
                (async/<!! (async/timeout 100))
                (is (= 1 (count (filter #{"ctx-child-1"} @closed))))
                (is (= 2 @started))
                (is (= 0 (count @broadcasts)))
                (let [events (session/load-session dir "sess-ctx")
                      texts (map :text events)]
                  (is (some #(= "keep me" %) texts) "original transcript preserved")
                  (is (= :session-created (:type (first events)))))
                (let [second-context (second @contexts)]
                  (is (some? second-context))
                  (is (str/includes? second-context "Role: Senior Dev"))
                  (is (str/includes? second-context "Topic: Java"))
                  (is (str/includes? second-context "Notes: updated"))))))
          (finally
            (system/stop-system sys))))
      (finally
        (delete-recursively! dir)))))
