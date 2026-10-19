(ns wingman.frontend.session-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [wingman.frontend.session :as session])
  (:import [java.nio.file Files]
           [java.util UUID]))

(def ^:dynamic *dir* nil)

(defn temp-dir-fixture
  [f]
  (let [dir (Files/createTempDirectory "session-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (binding [*dir* (.toString dir)]
      (try (f)
           (finally
             (doseq [^java.io.File file (.listFiles (.toFile dir))]
               (.delete file))
             (Files/delete dir))))))

(use-fixtures :each temp-dir-fixture)

(defn sample-event
  [type-kind]
  {:type type-kind :text (name type-kind)})

(deftest create-session-test
  (let [event (session/create-session *dir* {:title "T"})]
    (is (= :session-created (:type event)))
    (is (some? (:id event)))
    (is (= [event] (session/load-session *dir* (:id event))))))

(deftest append-and-load-test
  (let [created (session/create-session *dir*)]
    (session/append-event *dir* (:id created) (sample-event :transcript))
    (session/append-event *dir* (:id created) (sample-event :response))
    (let [events (session/load-session *dir* (:id created))]
      (is (= 3 (count events)))
      (is (= :transcript (:type (second events))))
      (is (= :response (:type (nth events 2))))
      (is (nil? (session/load-session *dir* (str (UUID/randomUUID))))))))

(deftest list-sessions-test
  (is (empty? (session/list-sessions *dir*)))
  (let [a (session/create-session *dir*)
        b (session/create-session *dir*)]
    (is (= (sort [(:id a) (:id b)])
           (session/list-sessions *dir*)))))
