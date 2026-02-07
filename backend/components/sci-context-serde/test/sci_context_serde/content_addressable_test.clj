(ns sci-context-serde.content-addressable-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
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

(deftest big-snapshot-small-delta-only-store-diff
  "大 snapshot 只改一点时：存第二份只需新增少量 blob，绝大部分复用。"
  (let [n 50
        bindings1 (into {} (map (fn [i] [(str "v" i) {:type :data :value i}]) (range n)))
        snap1 {:namespace "user" :bindings bindings1}
        ;; 只改一个 binding：v0 从 0 改成 999
        snap2 {:namespace "user" :bindings (assoc bindings1 "v0" {:type :data :value 999})}
        r1 (ca/snapshot->root+blobs snap1)
        r2 (ca/snapshot->root+blobs snap2)
        hashes1 (set (keys (:blobs r1)))
        hashes2 (set (keys (:blobs r2)))
        reused (set/intersection hashes1 hashes2)
        new-in-r2 (set/difference hashes2 hashes1)]
    (is (= n (count hashes1)) "第一份有 n 个 blob")
    (is (= n (count hashes2)) "第二份也有 n 个 blob")
    (is (= (dec n) (count reused)) "第二份里 n-1 个 hash 与第一份相同，可复用")
    (is (= 1 (count new-in-r2)) "只多 1 个新 hash，存第二份时只需多存这 1 个 blob")))
