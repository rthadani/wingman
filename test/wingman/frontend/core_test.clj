(ns wingman.frontend.core-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.capture-control :as capture-control]
            [wingman.frontend.system :as system]
            [org.httpkit.client :as http]))

(defn- free-port []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "interview-sessions"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(deftest start-stop-test
  (let [cfg {}
        system (system/build-system cfg)]
    (is (map? system))
    (is (contains? system :server))))

(deftest system-start-stop-test
  (let [port (free-port)
        dir (temp-dir)
        sys (system/start-system
             (system/build-system {:port port :sessions-dir dir}))]
    (try
      (is (fn? (:server sys)))
      (let [base (str "http://localhost:" port)
            sessions (deref (http/get (str base "/sessions")))
            created (with-redefs [capture-control/start! (fn [_ _] nil)]
                      (deref (http/post (str base "/api/session"))))
            id (let [body (:body created)]
                 (if (map? body)
                   (:id body)
                   (:id (json/parse-string (str body) true))))
            page (deref (http/get (str base "/session/" id)))]
        (is (= 200 (:status sessions)))
        (is (str/includes? (str (:body sessions)) "session-list"))
        (is (= 200 (:status created)))
        (is (string? id))
        (is (= 200 (:status page)))
        (is (str/includes? (str (:body page)) (str "/api/session/" id "/sse"))))
      (finally
        (system/stop-system sys)
        (delete-recursively! dir)
        (let [res (try (deref (http/get (str "http://localhost:" port "/sessions")))
                       (catch Throwable _ {:error :connection-failed}))]
          (is (or (:error res) (not= 200 (:status res)))))))))
