(ns sci-context-serde.classify-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.classify :as classify]))

(deftest classify-value
  (testing "edn"
    (is (= :edn (classify/classify-value 1)))
    (is (= :edn (classify/classify-value {:a 1}))))
  (testing "atom"
    (is (= :atom (classify/classify-value (atom 1)))))
  (testing "function"
    (is (= :function (classify/classify-value (fn [x] x)))))
  (testing "unserializable"
    (is (= :unserializable (classify/classify-value (Object.))))))
