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

(deftest min-audio-dbfs-default-test
  (is (= -50.0 (:min-audio-dbfs (config/load-config {})))))

(deftest min-audio-dbfs-env-test
  (with-redefs [config/env (fn [n] (when (= "MIN_AUDIO_DBFS" n) "-45.5"))]
    (is (= -45.5 (:min-audio-dbfs (config/load-config {}))))))

(deftest bad-min-audio-dbfs-falls-back-test
  (with-redefs [config/env (fn [n] (when (= "MIN_AUDIO_DBFS" n) "not-a-number"))]
    (is (= -50.0 (:min-audio-dbfs (config/load-config {}))))))
