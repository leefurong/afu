(ns sci-context-serde.serialize-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.classify :as classify]
            [sci-context-serde.serialize :as serialize]))

(deftest serialize-edn
  (is (= {:type :data :value 1} (serialize/serialize-edn 1)))
  (is (= {:type :data :value {:a 1}} (serialize/serialize-edn {:a 1}))))

(deftest serialize-atom
  (is (= {:type :atom :value 2} (serialize/serialize-atom (atom 2))))
  (is (nil? (serialize/serialize-atom (atom (fn []))))))

(deftest serialize-one
  (is (= {:type :data :value 1} (serialize/serialize-one 'x 1 {})))
  (is (= {:type :atom :value 3} (serialize/serialize-one 'a (atom 3) {})))
  (is (nil? (serialize/serialize-one 'x (fn []) {})))
  (testing "function with recent-forms"
    (let [forms '((defn f [x] (+ x 1)))]
      (is (= :function (classify/classify-value (fn []))))
      (is (some? (:source (serialize/serialize-one 'f (fn [x] (+ x 1)) {:recent-forms forms})))))))
