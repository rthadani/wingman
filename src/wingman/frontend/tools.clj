(ns wingman.frontend.tools
  (:require [clojure.java.io :as io]))

(def tool-names
  ["web_search"])

(def skill-names
  ["system-design-interview" "engineering-pilot"])

(defn stage-tools!
  [{:keys [tools-dir]}]
  (let [context-dir (io/file (System/getProperty "java.io.tmpdir")
                             (str "interview-context-" (System/currentTimeMillis)
                                  "-" (rand-int 1000000)))]
    (.mkdirs context-dir)
    (doseq [name tool-names]
      (let [src (io/file (or tools-dir "tools") name)
            dst (io/file context-dir "tools" name)]
        (.mkdirs (.getParentFile dst))
        (io/copy src dst)
        (.setExecutable dst true true)))
    (doseq [name skill-names]
      (let [src (io/file "resources" "skills" name "SKILL.md")
            dst (io/file context-dir "skills" name "SKILL.md")]
        (when (.exists src)
          (.mkdirs (.getParentFile dst))
          (io/copy src dst))))
    (let [agent-md (io/file "AGENT.md")]
      (when (.exists agent-md)
        (io/copy agent-md (io/file context-dir "AGENT.md"))))
    (.getPath context-dir)))

(defn skip-marked? [context-dir]
  (.exists (io/file context-dir ".skip-suggestion")))

(defn clear-skip-marker! [context-dir]
  (let [f (io/file context-dir ".skip-suggestion")]
    (when (.exists f)
      (io/delete-file f true))))

(defn cleanup-context-dir!
  [context-dir {:keys [archive-dir]}]
  (let [dir (io/file context-dir)]
    (if (and archive-dir (.exists dir))
      (do (.mkdirs (io/file archive-dir))
          (let [dest (io/file archive-dir (.getName dir))]
            (if (.renameTo dir dest)
              (.getPath dest)
              (do (run! io/delete-file (file-seq dir))
                  (.getPath dest)))))
      (when (.exists dir)
        (run! io/delete-file (reverse (file-seq dir)))
        nil))))
