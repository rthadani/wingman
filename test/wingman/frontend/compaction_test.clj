(ns wingman.frontend.compaction-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [wingman.frontend.compaction :as compaction]
            [wingman.frontend.session :as session])
  (:import [java.nio.file Files]))

(def ^:dynamic *dir* nil)

(defn temp-dir-fixture
  [f]
  (let [dir (Files/createTempDirectory "compaction-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (binding [*dir* (.toString dir)]
      (try (f)
           (finally
             (doseq [^java.io.File file (.listFiles (.toFile dir))]
               (.delete file))
             (Files/delete dir))))))

(use-fixtures :each temp-dir-fixture)

(defn write-session
  [events]
  (let [created (session/create-session *dir*)]
    (doseq [e events]
      (session/append-event *dir* (:id created) e))
    (:id created)))

(deftest compact-noop-test
  (let [id (write-session [{:type :transcript :text "a"}
                           {:type :note :text "n"}
                           {:type :tool-call :tool :x :args {} :result "ok"}])]
    (is (not (compaction/should-compact? (session/load-session *dir* id))))
    (let [after (compaction/compact-session! *dir* id)]
      (is (= (session/load-session *dir* id) after)))))

(deftest compact-reduces-test
  (let [events (concat [{:type :session-created :id "x" :meta {} :created-at "t"}]
                       (for [i (range 120)] {:type :transcript :text (str "t" i) :ts i})
                       (for [i (range 100)] {:type :response :text (str "r" i) :ts i})
                       [{:type :note :text "keep"}])
        id (write-session events)]
    (is (compaction/should-compact? (session/load-session *dir* id)))
    (let [after (compaction/compact-session! *dir* id)
          reloaded (session/load-session *dir* id)]
      (is (< (count after) (count events)))
      (is (= after reloaded))
      (is (every? #(not (contains? #{:transcript :response} (:type %))) after))
      (is (contains? (set (map #(select-keys % [:type :of :count]) after))
                     {:type :summary :of :transcript :count 120}))
      (is (= [{:type :note :text "keep"}]
             (filter #(= :note (:type %)) after)))
      (is (= (:type (first events)) (:type (first after)))))))

(deftest should-compact-threshold-test
  (is (not (compaction/should-compact? (vec (repeat 200 {:type :note}))))
      "not over threshold")
  (is (compaction/should-compact? (vec (repeat 201 {:type :note})))))
