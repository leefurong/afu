(ns sci-context-serde.edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.edn :as edn]))

(deftest edn-serializable
  (testing "primitives"
    (is (edn/edn-serializable? nil))
    (is (edn/edn-serializable? true))
    (is (edn/edn-serializable? 1))
    (is (edn/edn-serializable? "a")))
  (testing "collections of primitives"
    (is (edn/edn-serializable? {:a 1}))
    (is (edn/edn-serializable? [1 2]))
    (is (edn/edn-serializable? #{1 2})))
  (testing "nested"
    (is (edn/edn-serializable? {:x [1 {:y 2}]})))
  (testing "not serializable"
    (is (not (edn/edn-serializable? (fn []))))
    (is (not (edn/edn-serializable? (atom 1))))
    (is (not (edn/edn-serializable? (Object.))))))
