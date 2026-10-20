(ns wingman.frontend.renderer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hiccup2.core :as h]
            [wingman.frontend.renderer :as renderer]))

(def sample-sessions
  [{:id "s1" :title "Interview — A. Rivera"}
   {:id "s2" :title "Discovery — K. Osei"}])

(def sample-utterance
  {:id 1
   :speaker :interviewer
   :text "Can you walk me through how your team currently handles onboarding?"
   :timestamp "00:03:12"})

(defn- render [x] (str (h/html x)))

(deftest app-shell-renders-test
  (let [out (render (renderer/app-shell sample-sessions))]
    (is (str/includes? out "id=\"session-list\""))
    (is (str/includes? out "id=\"transcript-panel\""))
    (is (str/includes? out "id=\"sessions-sidebar\""))
    (is (str/includes? out "id=\"assistant-panel\""))
    (is (str/includes? out "Interview — A. Rivera"))
    (is (str/includes? out "href=\"/session/s1\""))))

(deftest session-item-delete-button-test
  (let [out (render ((var renderer/session-item) {:id "s1" :title "Interview"}))]
    (is (str/includes? out "hx-delete=\"/api/session/s1\""))
    (is (str/includes? out "hx-confirm=\"Delete this session?\""))
    (is (str/includes? out "hx-swap=\"delete\""))
    (is (str/includes? out "✕"))))

(deftest session-item-active-highlight-test
  (let [out (render ((var renderer/session-item) {:id "s1" :title "Interview"} "s1"))]
    (is (str/includes? out "bg-slate-700"))
    (is (str/includes? out "text-white"))
    (is (not (str/includes? out "hover:bg-slate-800")))))

(deftest transcript-fragment-test
  (let [out (render (renderer/transcript-fragment sample-utterance))]
    (is (str/includes? out "hx-swap-oob"))
    (is (str/includes? out "data-utterance=\"1\""))
    (is (str/includes? out "Can you walk me through how your team currently handles onboarding?"))))

(deftest response-fragment-final-test
  (let [out (render (renderer/response-fragment "Noted." true))]
    (is (str/includes? out "assistant-raw"))
    (is (str/includes? out "assistant-rendered"))
    (is (str/includes? out "Noted."))))

(deftest response-fragment-stream-test
  (let [out (render (renderer/response-fragment "Noted." false))]
    (is (str/includes? out "<p"))
    (is (str/includes? out "Noted."))))

(deftest history-fragment-response-test
  (let [out (render (renderer/history-fragment {:type :response :text "Answer."}))]
    (is (str/includes? out "assistant-raw"))
    (is (str/includes? out "assistant-rendered"))
    (is (str/includes? out "Answer."))))
