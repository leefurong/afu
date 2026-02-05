(ns sci-exec-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sci-exec :as se]))

(deftest eval-string-basic
  (testing "simple expressions return {:ok value}"
    (is (= {:ok 3} (se/eval-string "(+ 1 2)")))
    (is (= {:ok 6} (se/eval-string "(* 2 3)")))
    (is (= {:ok [1 2 3]} (se/eval-string "[1 2 3]")))
    (is (= {:ok 4} (se/eval-string "(count [1 2 3 4])")))))

(deftest eval-string-seq-and-functions
  (testing "seq, map, filter"
    (is (= {:ok '(2 3 4)} (se/eval-string "(map inc [1 2 3])")))
    (is (= {:ok [2 4]} (se/eval-string "(filter even? [1 2 3 4])")))
    (is (= {:ok 10} (se/eval-string "(reduce + [1 2 3 4])")))))

(deftest eval-string-error
  (testing "syntax or runtime error returns {:error ...}"
    (is (contains? (se/eval-string "(+ 1") :error))   ; 括号未闭合，语法错误
    (is (string? (:error (se/eval-string "(inc nil)")))) ; inc 不接受 nil，运行时错误
    (is (contains? (se/eval-string "(nth [] 99)") :error)))) ; 越界，运行时错误

(deftest eval-string-capture-out
  (testing "capture-out returns :out and :ok"
    (let [res (se/eval-string "(println \"hi\") 42" {:capture-out? true})]
      (is (contains? res :ok))
      (is (= 42 (:ok res)))
      (is (contains? res :out))
      (is (str/includes? (:out res) "hi")))))

(deftest eval-string-with-ctx-stateful
  (testing "ctx keeps def state"
    (let [ctx (se/create-ctx)]
      (is (= {:ok 1} (se/eval-string "(def x 1) x" {:ctx ctx})))
      (is (= {:ok 2} (se/eval-string "(def x 2) x" {:ctx ctx})))
      (is (= {:ok 2} (se/eval-string "x" {:ctx ctx}))))))

(comment (deftest eval-string-timeout
           (testing "timeout returns error"
             (let [res (se/eval-string "(range)" {:timeout-ms 50})]
               (is (contains? res :error))
               (is (str/includes? (:error res) "timed out")))))
         )

(deftest denied-symbols
  (testing "denied symbols return error"
    (is (contains? (se/eval-string "(slurp \"/nonexistent\")") :error))
    (is (contains? (se/eval-string "(eval (quote 1))") :error))))

(deftest eval-string-star-direct
  (testing "eval-string* returns value"
    (is (= 3 (se/eval-string* "(+ 1 2)")))
    (is (= 5 (se/eval-string* "(inc 4)")))))
