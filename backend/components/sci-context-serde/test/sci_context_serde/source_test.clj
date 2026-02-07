(ns sci-context-serde.source-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci-context-serde.source :as source]))

(deftest read-forms
  (testing "single form"
    (is (= [(list 'def 'x 1)] (source/read-forms "(def x 1)"))))
  (testing "multiple forms"
    (is (= 2 (count (source/read-forms "(def x 1) (def y 2)"))))))

(deftest defining-form
  (testing "def and defn"
    (is (source/defining-form? '(def a 1)))
    (is (source/defining-form? '(defn f [x] x)))
    (is (nil? (source/defining-symbol '(inc 1))))))
(deftest defining-symbol
  (is (= 'x (source/defining-symbol '(def x 1))))
  (is (= 'f (source/defining-symbol '(defn f [x] x)))))

(deftest defining-forms-by-name
  (let [by-name (source/defining-forms-by-name (source/read-forms "(def a 1) (defn b [x] x) (def c 2)"))]
    (is (= '(def a 1) (get by-name 'a)))
    (is (= 'defn (first (get by-name 'b))))
    (is (= 'c (source/defining-symbol (get by-name 'c))))))

(deftest find-source
  (let [forms (source/read-forms "(def x 1) (defn foo [y] (+ y 1))")]
    (is (string? (source/find-source 'foo forms)))
    (is (str/includes? (source/find-source 'foo forms) "defn"))
    (is (nil? (source/find-source 'bar forms)))))
