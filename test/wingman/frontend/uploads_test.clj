(ns wingman.frontend.uploads-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [wingman.frontend.uploads :as uploads]))

(deftest allowed-upload-extension-test
  (is (true? (uploads/allowed-upload? {:filename "notes.md"})))
  (is (true? (uploads/allowed-upload? {:filename "RESUME.MD"})))
  (is (true? (uploads/allowed-upload? {:filename "Mixed.Md"})))
  (is (false? (uploads/allowed-upload? {:filename "script.js"})))
  (is (false? (uploads/allowed-upload? {:filename "archive.tar.gz"})))
  (is (false? (uploads/allowed-upload? {:filename nil})))
  (is (false? (uploads/allowed-upload? {}))))

(deftest safe-original-name-test
  (is (= "resume.md" (uploads/safe-original-name {:filename "resume.md"})))
  (is (= "resume.md" (uploads/safe-original-name {:filename "/path/to/resume.md"})))
  (is (= "resume.md" (uploads/safe-original-name {:filename "C:\\Users\\me\\resume.md"})))
  (is (= "passwd.md" (uploads/safe-original-name {:filename "../../../etc/passwd.md"})))
  (is (= "resume.md" (uploads/safe-original-name {:filename "re<sume>.md"})))
  (is (= 120 (count (uploads/safe-original-name {:filename (str (apply str (repeat 200 "a")) ".md")})))))

(deftest valid-utf8-test
  (is (true? (uploads/valid-utf8? {:bytes (.getBytes "hello world" "UTF-8")})))
  (is (false? (uploads/valid-utf8? {:bytes (byte-array [0x80 0x81 0x82])})))
  (is (false? (uploads/valid-utf8? {:bytes (.getBytes "hello\u0000world" "UTF-8")}))))

(deftest within-limits-test
  (is (true? (uploads/within-limits? [])))
  (is (true? (uploads/within-limits? [{:bytes (byte-array uploads/max-file-bytes)}])))
  (is (false? (uploads/within-limits? [{:bytes (byte-array (inc uploads/max-file-bytes))}])))
  (is (true? (uploads/within-limits? (repeat uploads/max-files {:bytes (byte-array 1)}))))
  (is (false? (uploads/within-limits? (repeat (inc uploads/max-files) {:bytes (byte-array 1)}))))
  (is (true? (uploads/within-limits? (repeat 4 {:bytes (byte-array uploads/max-file-bytes)}))))
  (is (false? (uploads/within-limits? (repeat 5 {:bytes (byte-array uploads/max-file-bytes)})))))
