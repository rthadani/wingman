(ns wingman.frontend.loop
  (:require [clojure.java.io :as io]
            [wingman.frontend.agent :as agent]
            [wingman.frontend.tools :as tools]))

(defn start-session!
  [{:keys [tools-dir provider model name pi-bin context]}]
  (let [context-dir (tools/stage-tools! {:tools-dir tools-dir})
        context-file (io/file context-dir "context.md")
        _ (when (seq context) (spit context-file context))
        conn (agent/start-rpc-session!
              {:provider provider
               :model model
               :name name
               :context-dir context-dir
               :pi-bin pi-bin
               :append-system-prompt (when (.exists context-file)
                                       (.getAbsolutePath context-file))})]
    {:context-dir context-dir :conn conn}))

(defn run-turn!
  [session prompt line-ch]
  (tools/clear-skip-marker! (:context-dir session))
  (let [{:keys [text timeout? closed?]} (agent/run-turn! (:conn session) prompt line-ch)]
    {:text text :skip? (tools/skip-marked? (:context-dir session)) :timeout? (boolean timeout?) :closed? (boolean closed?)}))

(defn close-session!
  [session]
  (when session
    (agent/close-session! (:conn session))
    (tools/cleanup-context-dir! (:context-dir session) nil)))
