(ns wingman.frontend.core
  (:require [clojure.core.async :as async]
            [wingman.frontend.config :as config]
            [wingman.frontend.system :as system])
  (:gen-class))

(defn -main
  [& _args]
  (let [cfg (config/load-config {})
        sys (system/start-system (system/build-system cfg))]
    (println "interview server listening on port" (:port cfg))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (system/stop-system sys))))
    (async/<!! (async/chan))))
