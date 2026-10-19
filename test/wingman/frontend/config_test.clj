(ns wingman.frontend.config-test
  (:require [clojure.test :refer [deftest is]]
            [wingman.frontend.config :as config]))

(deftest opts-override-defaults-test
  (let [c (config/load-config {:port 9999 :sessions-dir "/tmp/x" :provider "p"})]
    (is (= 9999 (:port c)))
    (is (= "/tmp/x" (:sessions-dir c)))
    (is (= "p" (:provider c)))))

(deftest static-defaults-test
  (let [c (config/load-config {})]
    (is (= "data/sessions" (:sessions-dir c)))
    (is (= "tools" (:tools-dir c)))
    (is (= "data/archive" (:archive-dir c)))))

(deftest port-parses-from-env-test
  (with-redefs [config/env (fn [n] (when (= "PORT" n) "8080"))]
    (is (= 8080 (:port (config/load-config {}))))))

(deftest bad-env-port-falls-back-to-7777-test
  (with-redefs [config/env (fn [n] (when (= "PORT" n) "not-a-number"))]
    (is (= 7777 (:port (config/load-config {}))))))
