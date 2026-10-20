(ns wingman.frontend.handlers-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [wingman.frontend.capture-control :as capture-control]
            [wingman.frontend.handlers :as handlers]
            [wingman.frontend.loop :as agent-loop]
            [wingman.frontend.session :as session]
            [wingman.frontend.sse :as sse]
            [wingman.frontend.system :as system]
            [wingman.frontend.whisper :as whisper]
            [org.httpkit.client :as http]))

(defn- free-port []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "interview-handlers"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(defn- body-str [res]
  (let [b (:body res)]
    (if (string? b) b (str b))))

(deftest session-create-triggers-capture-test
  (let [calls (atom [])]
    (with-redefs [session/create-session (fn [_] {:id "sess-123"})
                  capture-control/start! (fn [url id] (swap! calls conj [url id]))]
      (let [res (handlers/session-create-handler
                 {:wingman.frontend/sys {:config {:sessions-dir "x" :capture-url "http://c:7778"}}})]
        (is (= 200 (:status res)))
        (is (= {:id "sess-123"} (json/parse-string (:body res) true)))
        (is (= [["http://c:7778" "sess-123"]] @calls))))))

(deftest session-delete-handler-test
  (let [dir (temp-dir)]
    (try
      (let [id (:id (session/create-session dir))
            status-calls (atom [])
            stop-calls (atom [])]
        (with-redefs [capture-control/status! (fn [url on-ok _]
                                                (swap! status-calls conj url)
                                                (on-ok {:session-id id}))
                      capture-control/stop! (fn [url] (swap! stop-calls conj url))]
          (let [sys {:wingman.frontend/sys {:config {:sessions-dir dir :capture-url "http://c:7778"}}}
                unknown (handlers/session-delete-handler (assoc sys :path-params {:id "no-such"}))
                ok (handlers/session-delete-handler (assoc sys :path-params {:id id}))]
            (is (= 404 (:status unknown)))
            (is (= "unknown session" (:body unknown)))
            (is (= 204 (:status ok)))
            (is (not (.exists (io/file dir (str id ".edn")))))
            (is (= ["http://c:7778"] @status-calls))
            (is (= ["http://c:7778"] @stop-calls)))))
      (finally
        (delete-recursively! dir)))))

(deftest capture-stop-test
  (let [calls (atom [])]
    (with-redefs [capture-control/stop! (fn [url] (swap! calls conj url))]
      (let [res (handlers/capture-stop-handler
                 {:wingman.frontend/sys {:config {:capture-url "http://c:7778"}}})]
        (is (= 202 (:status res)))
        (is (= ["http://c:7778"] @calls))))))

(deftest capture-mute-unmute-test
  (let [calls (atom [])]
    (with-redefs [capture-control/mute! (fn [url] (swap! calls conj [:mute url]))
                  capture-control/unmute! (fn [url] (swap! calls conj [:unmute url]))]
      (let [sys {:wingman.frontend/sys {:config {:capture-url "http://c:7778"}}}
            m (handlers/capture-mute-handler sys)
            u (handlers/capture-unmute-handler sys)]
        (is (= 202 (:status m)))
        (is (= 202 (:status u)))
        (is (= [[:mute "http://c:7778"] [:unmute "http://c:7778"]] @calls))))))

(deftest pause-resume-handler-test
  (let [dir (temp-dir)
        sys (system/build-system {:sessions-dir dir})]
    (try
      (let [voice-pause (handlers/pause-handler {:wingman.frontend/sys sys :path-params {:kind "voice"}})]
        (is (= 202 (:status voice-pause)))
        (is (= "paused voice" (:body voice-pause)))
        (is (= {:voice true :text false} @(:paused sys))))
      (let [text-pause (handlers/pause-handler {:wingman.frontend/sys sys :path-params {:kind "text"}})]
        (is (= 202 (:status text-pause)))
        (is (= "paused text" (:body text-pause)))
        (is (= {:voice true :text true} @(:paused sys))))
      (let [bogus-pause (handlers/pause-handler {:wingman.frontend/sys sys :path-params {:kind "bogus"}})]
        (is (= 400 (:status bogus-pause)))
        (is (= "unknown kind" (:body bogus-pause)))
        (is (= {:voice true :text true} @(:paused sys))))
      (let [voice-resume (handlers/resume-handler {:wingman.frontend/sys sys :path-params {:kind "voice"}})]
        (is (= 202 (:status voice-resume)))
        (is (= "resumed voice" (:body voice-resume)))
        (is (= {:voice false :text true} @(:paused sys))))
      (let [text-resume (handlers/resume-handler {:wingman.frontend/sys sys :path-params {:kind "text"}})]
        (is (= 202 (:status text-resume)))
        (is (= "resumed text" (:body text-resume)))
        (is (= {:voice false :text false} @(:paused sys))))
      (let [bogus-resume (handlers/resume-handler {:wingman.frontend/sys sys :path-params {:kind "bogus"}})]
        (is (= 400 (:status bogus-resume)))
        (is (= "unknown kind" (:body bogus-resume)))
        (is (= {:voice false :text false} @(:paused sys))))
      (finally
        (system/stop-system sys)
        (delete-recursively! dir)))))

(defn- with-server [cfg test-fn]
  (let [dir (temp-dir)
        sys (system/start-system
             (system/build-system (merge {:port (free-port) :sessions-dir dir} cfg)))]
    (try
      (test-fn (str "http://localhost:" (get-in sys [:config :port])) dir sys)
      (finally
        (system/stop-system sys)
        (delete-recursively! dir)))))

(deftest pause-resume-routes-test
  (with-server {}
    (fn [base _dir sys]
      (is (= 202 (:status @(http/post (str base "/api/pause/voice")))))
      (is (true? (get @(:paused sys) :voice)))
      (is (= 202 (:status @(http/post (str base "/api/resume/voice")))))
      (is (false? (get @(:paused sys) :voice)))
      (is (= 400 (:status @(http/post (str base "/api/pause/bogus")))))
      (is (= 202 (:status @(http/post (str base "/api/pause/text")))))
      (is (true? (get @(:paused sys) :text)))
      (let [res @(http/get (str base "/api/pause/status"))]
        (is (= 200 (:status res)))
        (is (= {:voice false :text true} (json/parse-string (:body res) true)))))))

(deftest home-handler-test
  (with-server {}
    (fn [base _dir _sys]
      (let [res @(http/get (str base "/"))]
        (is (= 200 (:status res)))
        (is (not (str/includes? (body-str res) "/capture.js")))
        (is (not (str/includes? (body-str res) "sse-connect=\"/events\"")))
        (is (str/includes? (body-str res) "hx-post=\"/api/session/NONE/chat\""))
        (is (str/includes? (body-str res) "pause-voice-toggle"))
        (is (str/includes? (body-str res) "pause-text-toggle"))
        (is (str/includes? (body-str res) "/api/pause/"))))))

(deftest session-handler-rewrites-sse-and-chat-test
  (with-server {}
    (fn [base dir _sys]
      (let [id (:id (session/create-session dir))
            res @(http/get (str base "/session/" id))]
        (is (= 200 (:status res)))
        (is (str/includes? (body-str res) (str "sse-connect=\"/api/session/" id "/sse\"")))
        (is (str/includes? (body-str res) (str "hx-post=\"/api/session/" id "/chat\"")))
        (is (str/includes? (body-str res) "id=\"chat-form\""))
        (is (not (str/includes? (body-str res) "SESSION_ID")))
        (is (not (str/includes? (body-str res) "startCapture")))
        (is (not (str/includes? (body-str res) "clipboard-status-dot")))
        (is (str/includes? (body-str res) "pause-voice-toggle"))
        (is (str/includes? (body-str res) "pause-text-toggle"))))))

(deftest session-handler-injects-history-test
  (with-server {}
    (fn [base dir _sys]
      (let [id (:id (session/create-session dir))
            _ (session/append-event dir id {:type :transcript :speaker :candidate :text "history seed" :ts "00:01"})
            res @(http/get (str base "/session/" id))]
        (is (= 200 (:status res)))
        (is (str/includes? (body-str res) "history seed"))
        (is (str/includes? (body-str res) "utterance-card"))))))

(deftest chat-contract-test
  (with-server {}
    (fn [base dir _sys]
      (let [id (:id (session/create-session dir))
            called (promise)]
        (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                      agent-loop/close-session! (fn [_] nil)
                      agent-loop/run-turn!
                      (fn [_session _prompt _line-ch]
                        (deliver called true)
                        {:text "ok" :skip? true})]
          (let [bad-json @(http/post (str base "/api/session/" id "/chat")
                                     {:body "not json{"})
                bad-type @(http/post (str base "/api/session/" id "/chat")
                                     {:body (json/generate-string {:message 5})})
                ok @(http/post (str base "/api/session/" id "/chat")
                               {:body (json/generate-string {:message "hello"})})]
            (is (= 400 (:status bad-json)))
            (is (= 400 (:status bad-type)))
            (is (= 202 (:status ok)))
            (is (deref called 2000 false))))))))

(deftest audio-contract-test
  (with-server {}
    (fn [base dir _sys]
      (let [id (:id (session/create-session dir))]
        (is (= 202 (:status @(http/post (str base "/api/session/" id "/audio")
                                        {:body (.getBytes "chunk")}))))
        (is (= 202 (:status @(http/post (str base "/api/session/" id "/audio")))))))))

(deftest unknown-or-unsafe-session-id-test
  (with-server {}
    (fn [base _dir _sys]
      (is (= 404 (:status @(http/post (str base "/api/session/no-such-session/chat")
                                      {:body (json/generate-string {:message "hi"})}))))
      (is (= 404 (:status @(http/post (str base "/api/session/no-such-session/audio")
                                      {:body (.getBytes "chunk")}))))
      (is (= 404 (:status @(http/post (str base "/api/session/bad.id/chat")
                                      {:body (json/generate-string {:message "hi"})}))))
      (is (= 404 (:status @(http/post (str base "/api/session/a/b/chat")
                                      {:body (json/generate-string {:message "hi"})}))))
      (is (= 404 (:status @(http/post (str base "/api/session/x%2Ey/audio")
                                      {:body (.getBytes "chunk")}))))
      (is (= 404 (:status @(http/get (str base "/session/no-such-session")))))
      (is (= 404 (:status @(http/get (str base "/session/bad.id")))))
      (is (= 404 (:status @(http/get (str base "/api/session/no-such-session/sse")))))
      (is (= 404 (:status @(http/get (str base "/api/session/bad.id/sse"))))))))

(deftest sse-stream-delivers-frame-test
  (with-server {}
    (fn [_base dir sys]
      (let [id (:id (session/create-session dir))
            sock (java.net.Socket. "localhost" (get-in sys [:config :port]))]
        (try
          (.setSoTimeout sock 10000)
          (let [out (.getOutputStream sock)
                _ (doto out
                    (.write (.getBytes (str "GET /api/session/" id "/sse HTTP/1.1\r\nHost: localhost\r\n\r\n")))
                    (.flush))
                rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))]
            (is (.startsWith (.readLine rdr) "HTTP/1.1 200"))
            (let [headers (->> (repeatedly #(.readLine rdr))
                                (take-while #(not= "" %))
                                (doall))]
              (is (some #(str/includes? % "text/event-stream") headers)))
            (sse/broadcast (:sse-registry sys) id "transcript" "<b>hello</b>")
            (let [event-line (loop []
                               (let [l (.readLine rdr)]
                                 (if (= "event: transcript" l)
                                   l
                                   (recur))))
                  l2 (.readLine rdr)
                  l3 (.readLine rdr)]
              (is (= "event: transcript" event-line))
              (is (= "data: <b>hello</b>" l2))
              (is (= "" l3))))
          (finally
            (.close sock)))))))

(deftest chat-form-encoded-test
  (with-server {}
    (fn [base dir _sys]
      (let [id (:id (session/create-session dir))
            called (promise)]
        (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                      agent-loop/close-session! (fn [_] nil)
                      agent-loop/run-turn!
                      (fn [_session prompt _line-ch]
                        (deliver called prompt)
                        {:text "ok" :skip? true})]
          (let [ok @(http/post (str base "/api/session/" id "/chat")
                               {:body "message=hello+world"
                                :headers {"Content-Type" "application/x-www-form-urlencoded"}})
                missing @(http/post (str base "/api/session/" id "/chat")
                                    {:body "other=x"
                                     :headers {"Content-Type" "application/x-www-form-urlencoded"}})]
            (is (= 202 (:status ok)))
            (is (= 400 (:status missing)))
            (is (str/includes? (deref called 2000 "") "hello world"))))))))

(deftest ws-frame-routing-test
  (let [dir (temp-dir)
        sys (system/build-system {:port (free-port) :sessions-dir dir})]
    (try
      (let [id (:id (session/create-session dir))]
        (testing "binary frame routes raw pcm into audio pipeline"
          (let [chunk (promise)]
            (with-redefs [whisper/whisper-available? (constantly true)
                          whisper/transcribe-pcm
                          (fn [bytes]
                            (deliver chunk bytes)
                            {:text "" :segments []})]
              (handlers/handle-ws-frame sys id (byte-array [0 1 2 3 4]))
              (let [delivered (deref chunk 2000 nil)]
                (is (some? delivered))
                (is (= 4 (count delivered)))
                (is (= (vec (byte-array [1 2 3 4])) (vec delivered)))))))
        (testing "clipboard text frame routes payload into chat pipeline"
          (let [msg (promise)]
            (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                          agent-loop/close-session! (fn [_] nil)
                          agent-loop/run-turn!
                          (fn [_session prompt _line-ch]
                            (deliver msg prompt)
                            {:text "ok" :skip? true})]
              (handlers/handle-ws-frame
               sys id
               (json/generate-string {:type "clipboard.update" :payload "the question"}))
              (is (str/includes? (deref msg 2000 "") "the question")))))
        (testing "malformed or wrong-type frames are ignored"
          (let [msg (promise)]
            (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                          agent-loop/close-session! (fn [_] nil)
                          agent-loop/run-turn!
                          (fn [_session _prompt _line-ch]
                            (deliver msg true)
                            {:text "ok" :skip? true})]
              (handlers/handle-ws-frame sys id "not json{")
              (handlers/handle-ws-frame
               sys id
               (json/generate-string {:type "other" :payload "x"}))
              (is (false? (deref msg 500 false)))))))
      (finally
        (system/stop-system sys)
        (delete-recursively! dir)))))

(defn- masked-text-frame [^String payload]
  (let [bytes (.getBytes payload)
        n (count bytes)
        mask (byte-array [(byte 0x12) (byte 0x34) (byte 0x56) (byte 0x78)])
        out (java.io.ByteArrayOutputStream.)]
    (.write out 0x81)
    (.write out (bit-or 0x80 n))
    (.write out mask)
    (dotimes [i n]
      (.write out (bit-xor (aget bytes i) (aget mask (mod i 4)))))
    (.toByteArray out)))

(deftest ws-endpoint-test
  (with-server {}
    (fn [_base dir sys]
      (let [port (get-in sys [:config :port])]
        (testing "unknown session rejected before handshake"
          (let [sock (java.net.Socket. "localhost" port)]
            (try
              (.setSoTimeout sock 5000)
              (doto (.getOutputStream sock)
                (.write (.getBytes "GET /ws/session/no-such-session HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"))
                (.flush))
              (is (.startsWith (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock))))
                               "HTTP/1.1 404"))
              (finally
                (.close sock)))))
        (testing "valid session upgrades and delivers a masked text frame"
          (let [id (:id (session/create-session dir))
                sock (java.net.Socket. "localhost" port)]
            (try
              (.setSoTimeout sock 10000)
              (doto (.getOutputStream sock)
                (.write (.getBytes (str "GET /ws/session/" id " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")))
                (.flush))
              (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))]
                (is (.startsWith (.readLine rdr) "HTTP/1.1 101"))
                (let [headers (->> (repeatedly #(.readLine rdr))
                                   (take-while #(not= "" %))
                                   (doall))]
                  (is (some #(.equalsIgnoreCase ^String % "Upgrade: websocket") headers))))
              (let [called (promise)]
                (with-redefs [agent-loop/start-session! (fn [_opts] {:context-dir "x" :conn {}})
                              agent-loop/close-session! (fn [_] nil)
                              agent-loop/run-turn!
                              (fn [_session prompt _line-ch]
                                (deliver called prompt)
                                {:text "ok" :skip? true})]
                  (.write (.getOutputStream sock)
                          (masked-text-frame "{\"type\":\"clipboard.update\",\"payload\":\"hello ws\"}"))
                  (.flush (.getOutputStream sock))
                  (is (str/includes? (deref called 3000 "") "hello ws"))))
              (finally
                (.close sock)))))))))
