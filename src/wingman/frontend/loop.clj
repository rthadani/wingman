(ns wingman.frontend.loop
  (:require [wingman.frontend.agent :as agent]
            [wingman.frontend.tools :as tools]))

(defn start-session!
  [{:keys [tools-dir provider model name pi-bin]}]
  (let [context-dir (tools/stage-tools! {:tools-dir tools-dir})
        conn (agent/start-rpc-session!
              {:provider provider
               :model model
               :name name
               :context-dir context-dir
               :pi-bin pi-bin})]
    {:context-dir context-dir :conn conn}))

(defn run-turn!
  [session prompt line-ch]
  (tools/clear-skip-marker! (:context-dir session))
  (let [{:keys [text]} (agent/run-turn! (:conn session) prompt line-ch)]
    {:text text :skip? (tools/skip-marked? (:context-dir session))}))

(defn close-session!
  [session]
  (when session
    (agent/close-session! (:conn session))
    (tools/cleanup-context-dir! (:context-dir session) nil)))
