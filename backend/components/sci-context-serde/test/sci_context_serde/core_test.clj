(ns sci-context-serde.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.core :as core]
            [sci.core :as sci]))

(defn- make-ctx []
  (sci/init {:namespaces {'user {}}}))

(deftest roundtrip
  (let [ctx (make-ctx)
        code "(def x 1) (def data {:a 1}) (defn inc' [n] (+ n 1))"
        _    (sci/eval-string* ctx code)
        snap (core/serialize ctx {:recent-code code})
        ctx2 (make-ctx)
        _    (core/deserialize snap {:ctx ctx2})]
    (testing "data restored"
      (is (= 1 (sci/eval-string* ctx2 "x")))
      (is (= {:a 1} (sci/eval-string* ctx2 "data"))))
    (testing "function restored"
      (is (= 2 (sci/eval-string* ctx2 "(inc' 1)"))))))
