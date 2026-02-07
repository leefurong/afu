(ns sci-context-serde.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.store :as store]))

;; 内存版 backend，用于不依赖 Datomic 的单测
(deftype MemoryStore [^:volatile-mutable state]
  store/SnapshotStore
  (save! [_ snapshot] (set! state (store/snapshot->edn-str snapshot)) true)
  (load* [_] (store/edn-str->snapshot state)))

(deftest snapshot-edn-roundtrip
  (let [snapshot {:namespace "user" :bindings {"x" {:type :data :value 1}}}]
    (is (= snapshot (store/edn-str->snapshot (store/snapshot->edn-str snapshot))))))

(deftest store-protocol
  (let [backend (MemoryStore. nil)
        snapshot {:namespace "user" :bindings {"a" {:type :data :value 42}}}]
    (is (true? (store/save-snapshot! backend snapshot)))
    (is (= snapshot (store/load-snapshot backend)))
    (store/save-snapshot! backend {:namespace "user" :bindings {}})
    (is (= {:namespace "user" :bindings {}} (store/load-snapshot backend)))))
