(ns wingman.frontend.loop-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.agent :as agent]
            [wingman.frontend.loop :as iloop]
            [wingman.frontend.tools :as tools]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "interview-loop"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(deftest run-turn-clears-marker-and-reports-skip-test
  (let [dir (temp-dir)
        marker (io/file dir ".skip-suggestion")
        sent (atom nil)]
    (try
      (spit marker "x")
      (with-redefs [agent/run-turn! (fn [c prompt _line-ch] (reset! sent [c prompt]) {:text "ok"})]
        (let [res (iloop/run-turn! {:context-dir dir :conn :conn} "prompt" nil)]
          (is (= {:text "ok" :skip? false :timeout? false :closed? false} res))
          (is (false? (.exists marker)))
          (is (= [:conn "prompt"] @sent))))
      (finally
        (delete-recursively! dir)))))

(deftest run-turn-reports-skip-when-marker-present-test
  (let [dir (temp-dir)
        marker (io/file dir ".skip-suggestion")]
    (try
      (with-redefs [agent/run-turn! (fn [_ _ _] (spit marker "x") {:text ""})]
        (is (= {:text "" :skip? true :timeout? false :closed? false} (iloop/run-turn! {:context-dir dir :conn {}} "p" nil))))
      (finally
        (delete-recursively! dir)))))

(deftest start-and-close-session-test
  (let [dir (temp-dir)
        started (atom nil)
        closed (atom false)]
    (try
      (with-redefs [tools/stage-tools! (fn [_] dir)
                    agent/start-rpc-session! (fn [opts] (reset! started opts) {:proc :p :in :i :out-ch :o})
                    agent/close-session! (fn [_] (reset! closed true))
                    tools/cleanup-context-dir! (fn [_ _] nil)]
        (let [session (iloop/start-session! {:tools-dir "tools" :provider "p" :model "m" :name "n" :pi-bin "pi"})]
          (is (= dir (:context-dir session)))
          (is (= {:provider "p" :model "m" :name "n" :context-dir dir :pi-bin "pi" :append-system-prompt nil} @started))
          (iloop/close-session! session)
          (is (true? @closed))))
      (finally
        (delete-recursively! dir)))))

(deftest close-session-nil-test
  (is (nil? (iloop/close-session! nil))))

(deftest start-session-writes-context-file-test
  (let [dir (temp-dir)
        started (atom nil)]
    (try
      (with-redefs [tools/stage-tools! (fn [_] dir)
                    agent/start-rpc-session! (fn [opts] (reset! started opts) {:proc :p :in :i :out-ch :o})
                    tools/cleanup-context-dir! (fn [_ _] nil)]
        (let [ctx-md "## Interview context\nCompany: Acme\n"
              session (iloop/start-session! {:tools-dir "tools" :name "n" :context ctx-md})]
          (is (= dir (:context-dir session)))
          (let [path (:append-system-prompt @started)]
            (is (string? path))
            (is (.isAbsolute (io/file path)))
            (is (= ctx-md (slurp path))))))
      (finally
        (delete-recursively! dir)))))
