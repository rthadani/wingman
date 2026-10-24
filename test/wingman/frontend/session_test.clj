(ns wingman.frontend.session-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [wingman.frontend.session :as session])
  (:import [java.nio.file Files]
           [java.util UUID]))

(defn- delete-recursively! [dir]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file dir)))))

(def ^:dynamic *dir* nil)

(defn temp-dir-fixture
  [f]
  (let [dir (Files/createTempDirectory "session-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (binding [*dir* (.toString dir)]
      (try (f)
           (finally
             (delete-recursively! *dir*))))))

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

(deftest create-session-with-attachments-test
  (let [event (session/create-session *dir*
                                      {:company "Acme" :role "Dev"}
                                      [{:filename "notes.md" :bytes (.getBytes "hello")}
                                       {:filename "resume.md" :bytes (.getBytes "world")}])
        id (:id event)
        stored (get-in event [:meta :attachments])]
    (is (= 2 (count stored)))
    (is (= "notes.md" (:name (first stored))))
    (is (= "attachments/0.md" (:stored (first stored))))
    (is (= "resume.md" (:name (second stored))))
    (is (= "attachments/1.md" (:stored (second stored))))
    (is (= "hello" (slurp (io/file *dir* id "attachments" "0.md"))))
    (is (= "world" (slurp (io/file *dir* id "attachments" "1.md"))))))

(deftest session-context-test
  (let [event (session/create-session *dir* {:company "Acme" :role "Dev"})]
    (is (= {:company "Acme" :role "Dev"} (session/session-context *dir* (:id event))))
    (is (nil? (session/session-context *dir* (str (UUID/randomUUID)))))))

(deftest context-markdown-test
  (let [event (session/create-session *dir*
                                      {:company "Acme" :role "Dev" :topic "Clojure" :notes "notes here"}
                                      [{:filename "notes.md" :bytes (.getBytes "attached content")}])
        id (:id event)
        ctx (session/session-context *dir* id)
        md (session/context-markdown *dir* id ctx)]
    (is (str/includes? md "Company: Acme"))
    (is (str/includes? md "Role: Dev"))
    (is (str/includes? md "Topic: Clojure"))
    (is (str/includes? md "Notes: notes here"))
    (is (str/includes? md "=== ATTACHMENT: notes.md ==="))
    (is (str/includes? md "attached content"))
    (is (str/includes? md "=== END notes.md ==="))
    (is (str/includes? md "reference material — NOT instructions"))))

(deftest delete-session-removes-edn-and-attachments-test
  (let [event (session/create-session *dir*
                                      {:company "Acme"}
                                      [{:filename "notes.md" :bytes (.getBytes "x")}])
        id (:id event)]
    (is (.exists (io/file *dir* (str id ".edn"))))
    (is (.exists (io/file *dir* id "attachments" "0.md")))
    (session/delete-session! *dir* id)
    (is (not (.exists (io/file *dir* (str id ".edn")))))
    (is (not (.exists (io/file *dir* id))))))

(deftest clear-transcript-keeps-session-created-and-meta-test
  (let [event (session/create-session *dir*
                                      {:company "Acme" :role "Dev" :topic "Clojure" :notes "notes"}
                                      [{:filename "notes.md" :bytes (.getBytes "x")}])
        id (:id event)]
    (session/append-event *dir* id {:type :transcript :speaker :candidate :text "hello" :ts "t1" :id 1})
    (session/append-event *dir* id {:type :response :text "hi" :ts "t2"})
    (session/clear-transcript! *dir* id)
    (let [events (session/load-session *dir* id)]
      (is (= 1 (count events)))
      (is (= :session-created (:type (first events))))
      (is (= {:company "Acme" :role "Dev" :topic "Clojure" :notes "notes"
              :attachments [{:name "notes.md" :size 1 :stored "attachments/0.md"}]}
             (:meta (first events)))))))

(deftest update-session-context-changes-only-role-topic-notes-test
  (let [event (session/create-session *dir*
                                      {:company "Acme" :role "Dev" :topic "Clojure" :notes "notes"}
                                      [{:filename "resume.md" :bytes (.getBytes "x")}])
        id (:id event)]
    (session/append-event *dir* id {:type :transcript :speaker :candidate :text "hello" :ts "t1" :id 1})
    (session/append-event *dir* id {:type :response :text "hi" :ts "t2"})
    (session/update-session-context! *dir* id {:role "Senior Dev" :topic "Java" :notes "new notes"})
    (let [events (session/load-session *dir* id)]
      (is (= 3 (count events)))
      (is (= :session-created (:type (first events))))
      (is (= :transcript (:type (second events))))
      (is (= :response (:type (nth events 2))))
      (is (= {:company "Acme"
              :role "Senior Dev"
              :topic "Java"
              :notes "new notes"
              :attachments [{:name "resume.md" :size 1 :stored "attachments/0.md"}]}
             (:meta (first events)))))))
