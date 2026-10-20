(ns wingman.capture.server-test
  (:require [wingman.capture.audio :as audio]
            [wingman.capture.clipboard :as clipboard]
            [wingman.capture.server :as srv]
            [wingman.capture.transport :as transport]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [org.httpkit.client :as client])
  (:import (java.net ServerSocket)))

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- deliver-promise [v]
  (doto (promise) (deliver v)))

(deftest create-session-test
  (with-redefs [client/post (fn [_url _opts]
                              (deliver-promise {:status 200 :body "{\"id\":\"sess-9\"}"}))]
    (is (= "sess-9" (srv/create-session! "http://localhost:7777")))))

(deftest current-status-test
  (let [state (atom {:status :idle :session-id nil})]
    (is (= {:status :idle :session-id nil} (srv/current-status state)))))

(deftest start-capture-test
  (let [state (atom {:status :idle})
        ws-urls (atom [])
        sender-ch (async/chan 16)
        audio-fn (atom nil)
        clip-fn (atom nil)]
    (with-redefs [transport/keep-connected! (fn [ws-atom ws-url _client _stop-ch]
                                              (swap! ws-urls conj ws-url)
                                              (reset! ws-atom {:ws :fake :closed (async/chan)}))
                  transport/start-sender! (fn [_ws-atom] sender-ch)
                  audio/start-audio-capture! (fn [f _mixers] (reset! audio-fn f) [{:dispatcher (Object.)}])
                  clipboard/start-clipboard-watch! (fn [f] (reset! clip-fn f))]
      (srv/start-capture! state "http://localhost:7777" "sess-1")
      (is (= :capturing (:status @state)))
      (is (= "sess-1" (:session-id @state)))
      (is (= ["ws://localhost:7777/ws/session/sess-1"] @ws-urls))
      (is (some? @audio-fn))
      (is (some? @clip-fn))
      (@audio-fn :candidate (byte-array [1 2 3]))
      (let [[msg _port] (async/alts!! [sender-ch (async/timeout 1000)])]
        (is (= :audio (first msg)))
        (is (= [1 2 3] (vec (second msg))))
        (is (= :candidate (nth msg 2)))))))

(deftest stop-capture-test
  (let [state (atom {:status :capturing
                     :session-id "sess-1"
                     :ws-atom (atom {:ws :fake :closed (async/chan)})
                     :stop-ch (async/chan)
                     :sender (async/chan 8)
                     :dispatchers [{:dispatcher (Object.)}]})
        stopped (atom {:audio false :clipboard false})]
    (with-redefs [audio/stop-audio-capture! (fn [_] (swap! stopped assoc :audio true))
                  clipboard/stop-clipboard-watch! (fn [] (swap! stopped assoc :clipboard true))]
      (srv/stop-capture! state)
      (is (= :idle (:status @state)))
      (is (nil? (:session-id @state)))
      (is (:audio @stopped))
      (is (:clipboard @stopped)))))

(deftest mute-drops-and-unmute-resumes-test
  (let [state (atom {:status :idle})
        sender-ch (async/chan 16)
        audio-fn (atom nil)]
    (with-redefs [transport/keep-connected! (fn [_a _b _c _d] nil)
                  transport/start-sender! (fn [_] sender-ch)
                  audio/start-audio-capture! (fn [f _mixers] (reset! audio-fn f) [{:dispatcher (Object.)}])
                  clipboard/start-clipboard-watch! (fn [_] nil)]
      (srv/start-capture! state "http://localhost:7777" "sess-1")
      (srv/mute state {})
      (is (true? (:muted? @state)))
      (@audio-fn :candidate (byte-array [1 2 3]))
      (let [[msg _] (async/alts!! [sender-ch (async/timeout 300)])]
        (is (nil? msg)))
      (srv/unmute state {})
      (is (false? (:muted? @state)))
      (@audio-fn :candidate (byte-array [4 5 6]))
      (let [[msg _] (async/alts!! [sender-ch (async/timeout 1000)])]
        (is (= :audio (first msg)))
        (is (= [4 5 6] (vec (second msg))))))))

(deftest start-capture-assigns-speakers-test
  (let [state (atom {:status :idle :devices [{:name "Mic"} {:name "BlackHole"}]})
        captured (atom nil)]
    (with-redefs [audio/find-mixer (fn [n] (when (#{"Mic" "BlackHole"} n) (str "mixer-" n)))
                  transport/keep-connected! (fn [_a _b _c _d] nil)
                  transport/start-sender! (fn [_] (async/chan 16))
                  audio/start-audio-capture! (fn [_send-fn devices] (reset! captured devices) [{:dispatcher (Object.)}])
                  clipboard/start-clipboard-watch! (fn [_] nil)]
      (srv/start-capture! state "http://localhost:7777" "sess-1")
      (is (= [{:mixer "mixer-Mic" :speaker :candidate}
              {:mixer "mixer-BlackHole" :speaker :interviewer}]
             @captured)))))

(deftest resolve-devices-explicit-speaker-test
  (with-redefs [audio/find-mixer (fn [n] (when (#{"plughw:3" "mic"} n) (str "mixer-" n)))]
    (is (= [{:mixer "mixer-plughw:3" :speaker :interviewer}]
           (#'srv/resolve-devices [{:name "plughw:3" :speaker :interviewer}])))
    (is (= [{:mixer "mixer-mic" :speaker :candidate}
            {:mixer "mixer-plughw:3" :speaker :interviewer}]
           (#'srv/resolve-devices [{:name "mic"} {:name "plughw:3" :speaker :interviewer}])))
    (is (= [{:mixer "mixer-mic" :speaker :interviewer}
            {:mixer "mixer-plughw:3" :speaker :candidate}]
           (#'srv/resolve-devices [{:name "mic" :speaker :interviewer} {:name "plughw:3"}])))
    (is (= [{:mixer "mixer-mic" :speaker :candidate}
            {:mixer "mixer-plughw:3" :speaker :interviewer}]
           (#'srv/resolve-devices ["mic" "plughw:3"])))))

(deftest control-server-roundtrip-test
  (let [port (free-port)
        state (atom {:status :idle :session-id nil})
        srv' (srv/start-server! state port)]
    (try
      (let [st @(client/get (str "http://localhost:" port "/status"))]
        (is (= 200 (:status st)))
        (is (= {:status "idle" :session-id nil :muted? false} (json/parse-string (:body st) true))))
      (with-redefs [srv/create-session! (constantly "sess-7")
                    transport/keep-connected! (fn [_a _b _c _d] nil)
                    transport/start-sender! (fn [_] (async/chan 16))
                    audio/start-audio-capture! (fn [_ _] [{:dispatcher (Object.)}])
                    audio/stop-audio-capture! (fn [_] nil)
                    clipboard/start-clipboard-watch! (fn [_] nil)
                    clipboard/stop-clipboard-watch! (fn [] nil)]
        (let [started @(client/post (str "http://localhost:" port "/start")
                                    {:headers {"Content-Type" "application/json"}
                                     :body (json/generate-string {:agent-url "http://localhost:7777"})})]
          (is (= 200 (:status started)))
          (is (= {:session-id "sess-7" :status "capturing"} (json/parse-string (:body started) true))))
        (let [st @(client/get (str "http://localhost:" port "/status"))]
          (is (= "capturing" (:status (json/parse-string (:body st) true)))))
        (let [stopped @(client/post (str "http://localhost:" port "/stop"))]
          (is (= 200 (:status stopped)))
          (is (= {:status "idle"} (json/parse-string (:body stopped) true)))))
      (finally
        (srv/stop-server! srv')))))

(deftest start-retargets-different-session-test
  (let [state (atom {:status :idle})
        stop-calls (atom 0)]
    (with-redefs [transport/keep-connected! (fn [_a _b _c _d] nil)
                  transport/start-sender! (fn [_] (async/chan 16))
                  audio/start-audio-capture! (fn [_ _] (Object.))
                  audio/stop-audio-capture! (fn [_] (swap! stop-calls inc))
                  clipboard/start-clipboard-watch! (fn [_] nil)
                  clipboard/stop-clipboard-watch! (fn [] nil)]
      (srv/start state {:body (json/generate-string {:agent-url "http://localhost:7777" :session-id "sess-1"})})
      (is (= "sess-1" (:session-id (srv/current-status state))))
      (srv/start state {:body (json/generate-string {:agent-url "http://localhost:7777" :session-id "sess-2"})})
      (is (= "sess-2" (:session-id (srv/current-status state))))
      (is (= 1 @stop-calls)))
    (with-redefs [transport/keep-connected! (fn [_a _b _c _d] nil)
                  transport/start-sender! (fn [_] (async/chan 16))
                  audio/start-audio-capture! (fn [_ _] [{:dispatcher (Object.)}])
                  audio/stop-audio-capture! (fn [_] (swap! stop-calls inc))
                  clipboard/start-clipboard-watch! (fn [_] nil)
                  clipboard/stop-clipboard-watch! (fn [] nil)]
      (srv/start state {:body (json/generate-string {:agent-url "http://localhost:7777" :session-id "sess-2"})})
      (is (= "sess-2" (:session-id (srv/current-status state))))
      (is (= 1 @stop-calls)))))
