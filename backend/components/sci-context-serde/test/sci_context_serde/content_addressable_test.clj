(ns sci-context-serde.content-addressable-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci-context-serde.store.content-addressable :as ca]))

(deftest content-hash-deterministic
  (let [entry {:type :data :value 1}
        h1 (ca/content-hash entry)
        h2 (ca/content-hash entry)]
    (is (= h1 h2))
    (is (= 64 (count h1)))
    (is (re-matches #"[0-9a-f]{64}" h1))))

(deftest snapshot-roundtrip
  (let [snapshot {:namespace "user" :bindings {"x" {:type :data :value 1}
                                               "y" {:type :data :value 2}}}
        {:keys [root blobs]} (ca/snapshot->root+blobs snapshot)
        get-blob (fn [h] (get blobs h))
        restored (ca/root+blobs->snapshot root get-blob)]
    (is (= snapshot restored))))

(deftest same-content-same-hash
  (let [entry {:type :data :value 42}
        snap1 {:namespace "user" :bindings {"a" entry}}
        snap2 {:namespace "user" :bindings {"b" entry}}
        r1 (ca/snapshot->root+blobs snap1)
        r2 (ca/snapshot->root+blobs snap2)]
    (is (= 1 (count (:blobs r1))) "同一 entry 只存一份")
    (is (= (:blobs r1) (:blobs r2)) "两 snapshot 的 blobs 表相同")))
