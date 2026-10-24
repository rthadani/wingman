(ns wingman.frontend.renderer
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

(defn- speaker-class
  [speaker]
  (if (= :interviewer speaker)
    "text-xs font-semibold text-indigo-700"
    "text-xs font-semibold text-emerald-700"))

(defn- session-item
  ([s] (session-item s nil))
  ([s active-id]
   (let [active? (= (:id s) active-id)
         base "px-4 py-2 cursor-pointer flex items-center justify-between group"
         classes (if active?
                   (str base " bg-slate-700 text-white")
                   (str base " hover:bg-slate-800"))]
     [:li {:class classes}
      [:a {:href (str "/session/" (:id s))
           :class "block flex-1 truncate"}
       (:title s)]
      [:button {:class "text-xs text-slate-500 hover:text-red-400 px-1 opacity-0 group-hover:opacity-100"
                :hx-delete (str "/api/session/" (:id s))
                :hx-target "closest li"
                :hx-swap "delete"
                :hx-confirm "Delete this session?"}
       "✕"]])))

(defn app-shell
  [sessions]
  (h/html
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:title "Wingman Interview"]
     [:script {:src "https://unpkg.com/htmx.org@2.0.4"}]]
    [:body {:class "h-screen flex flex-col bg-slate-100 text-slate-800"}
     [:div {:class "flex flex-1 min-h-0"}
      [:aside {:id "sessions-sidebar"
               :class "w-[260px] flex-none bg-slate-900 text-slate-200 flex flex-col"
               :data-panel "sessions"}
       [:div {:class "px-4 py-3 text-sm font-semibold tracking-wide text-slate-100 border-b border-slate-700"} "Sessions"]
       [:nav {:class "flex-1 overflow-y-auto text-sm"}
        [:ul {:id "session-list"}
         (map session-item sessions)]]]
      [:main {:id "transcript-panel"
              :class "flex-1 min-w-0 flex flex-col bg-white"
              :data-panel "transcript"}
       [:div {:class "px-4 py-3 border-b border-slate-200 text-sm font-semibold"} "Transcript"]
       [:div {:class "flex-1 overflow-y-auto p-4 space-y-3"}]]
      [:aside {:id "assistant-panel"
               :class "w-[360px] flex-none bg-slate-50 border-l border-slate-200 flex flex-col"
               :data-panel "assistant"}
       [:div {:class "px-4 py-3 text-sm font-semibold border-b border-slate-200"} "Assistant"]
       [:div {:class "flex-1 overflow-y-auto p-4"}]]]]]))

(defn transcript-fragment
  [utterance]
  (h/html
   [:div {:class "utterance-card max-w-2xl rounded-lg border border-slate-200 shadow-sm p-3"
          :data-utterance (str (:id utterance))
          :hx-swap-oob "beforeend:#transcript-panel .space-y-3"}
    [:div {:class "flex items-baseline justify-between mb-1"}
     [:span {:class (speaker-class (:speaker utterance))} (name (:speaker utterance))]
     [:span {:class "text-xs text-slate-400"} (:timestamp utterance)]]
    [:p {:class "text-sm text-slate-700"} (:text utterance)]]))

(defn response-fragment
  [text done?]
  (h/html
   (if done?
     [:div {:class "assistant-turn"}
      [:pre {:class "assistant-raw" :style "display:none"} text]
      [:div {:class "assistant-rendered prose text-sm text-slate-700"}]]
     [:p {:class "text-sm text-slate-700 leading-relaxed"} text])))

(defn history-fragment
  [event]
  (case (:type event)
    :transcript
    (h/html
     [:div {:class "utterance-card max-w-2xl rounded-lg border border-slate-200 shadow-sm p-3"}
      [:div {:class "flex items-baseline justify-between mb-1"}
       [:span {:class (speaker-class (:speaker event))} (name (:speaker event))]
       [:span {:class "text-xs text-slate-400"} (:ts event)]]
      [:p {:class "text-sm text-slate-700"} (:text event)]])
    :response
    (h/html
     [:div {:class "assistant-turn"}
      [:pre {:class "assistant-raw" :style "display:none"} (:text event)]
      [:div {:class "assistant-rendered prose text-sm text-slate-700"}]])
    nil))

(defn session-list-fragment
  ([sessions] (session-list-fragment sessions nil))
  ([sessions active-id]
   (h/html
    [:ul {:id "session-list" :class "text-sm"}
     (map #(session-item % active-id) sessions)])))

(defn- context-value [v]
  (or v ""))

(defn session-header-fragment
  "One-line header showing company, role and topic, omitting blank values."
  [{:keys [company role topic]}]
  (let [parts (remove str/blank? [(context-value company) (context-value role) (context-value topic)])
        text (str/join " · " parts)]
    (h/html
     [:div {:id "session-header" :class "px-4 py-2 bg-slate-50 border-b border-slate-200 text-sm text-slate-700"}
      text])))

(defn context-editor-fragment
  "Form for editing role, topic and notes after the session has started."
  [id {:keys [role topic notes]}]
  (h/html
   [:div {:id "context-editor" :class "hidden px-4 py-3 bg-white border-b border-slate-200"}
    [:form {:action (str "/api/session/" id "/context") :method "post" :class "space-y-3"}
     [:div
      [:label {:class "block text-sm font-medium"} "Role"]
      [:input {:type "text" :name "role" :value (context-value role)
               :class "w-full text-sm rounded border-slate-300 mt-1 px-2 py-1 border"}]]
     [:div
      [:label {:class "block text-sm font-medium"} "Topic"]
      [:input {:type "text" :name "topic" :value (context-value topic)
               :class "w-full text-sm rounded border-slate-300 mt-1 px-2 py-1 border"}]]
     [:div
      [:label {:class "block text-sm font-medium"} "Notes"]
      [:textarea {:name "notes" :rows 4
                  :class "w-full text-sm rounded border-slate-300 mt-1 px-2 py-1 border"}
       (context-value notes)]]
     [:p {:class "text-xs text-amber-600"}
      "Saving restarts the assistant's conversation memory from the next reply."]
     [:div {:class "flex gap-2"}
      [:button {:type "submit"
                :class "text-xs bg-indigo-600 text-white px-3 py-1.5 rounded hover:bg-indigo-500"}
       "Save"]
      [:button {:type "button" :id "cancel-context"
                :class "text-xs bg-slate-200 text-slate-800 px-3 py-1.5 rounded hover:bg-slate-300"}
       "Cancel"]]]]))
