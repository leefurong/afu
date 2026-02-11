(ns memory-store-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [memory-store :as ms]))

(def ^:private embed-dim 4)

(defn- embed-fn [text]
  (vec (take embed-dim (cycle [(/ (count text) 100.0)]))))

(def ^:dynamic *base-dir* nil)
(def ^:dynamic *vec-path* nil)
(def ^:dynamic *store* nil)

(defn- vec-extension-exists? [base-path]
  (some (fn [ext] (.exists (io/file (str base-path ext))))
        [".dylib" ".so" ".dll"]))

(defn- vec-extension-path []
  (when-let [v (or (System/getenv "VEC_EXTENSION_PATH")
                   (System/getProperty "VEC_EXTENSION_PATH"))]
    (when-not (str/blank? v)
      (let [resolved (str (.getAbsolutePath (io/file v)))]
        (when (vec-extension-exists? resolved) resolved)))))

(defn with-temp-store [f]
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/memory-store-test-" (random-uuid))
        vec-path (vec-extension-path)]
    (when-not vec-path
      (throw (ex-info (str "sqlite-vec 未找到。先运行 backend/scripts/setup-vec.sh，或设置 VEC_EXTENSION_PATH")
                      {})))
    (when-not (vec-extension-exists? vec-path)
      (throw (ex-info (str "sqlite-vec extension not found at " vec-path ". Run: backend/scripts/setup-vec.sh")
                      {})))
    (try
      (binding [*base-dir* tmp-dir
                *vec-path* vec-path
                *store* (ms/->memory-store {:base-dir tmp-dir
                                            :embed-fn embed-fn
                                            :vec-extension-path vec-path
                                            :embed-dim embed-dim})]
        (f))
      (finally
        (try (io/delete-file (io/file tmp-dir) true) (catch Exception _))))))

(use-fixtures :each with-temp-store)

(deftest memory-store-opts-validation
  (testing "->memory-store 缺少 base-dir 或 embed-fn 时 throws"
    (is (thrown? AssertionError (ms/->memory-store {})))
    (is (thrown? AssertionError (ms/->memory-store {:base-dir "/tmp"})))
    (is (thrown? AssertionError (ms/->memory-store {:embed-fn identity})))))

(deftest memory-store-create-valid
  (testing "->memory-store 合法 opts 创建成功"
    (is (map? *store*))
    (is (contains? *store* :base-dir))
    (is (contains? *store* :embed-fn))))

(deftest memory-ensure
  (testing "->memory 为 key 创建/打开库"
    (is (= :agent-1-user-1 (ms/->memory *store* :agent-1-user-1)))
    (is (= :other-key (ms/->memory *store* :other-key)))))

(deftest remember-returns-id
  (testing "remember! 返回 content-id"
    (ms/->memory *store* :test)
    (let [id (ms/remember! *store* :test "用户喜欢 Clojure")]
      (is (string? id))
      (is (re-matches #"[0-9a-f-]{36}" id)))))

(deftest recall-after-remember
  (testing "remember! 后 recall 能取回 content"
    (ms/->memory *store* :test)
    (let [id (ms/remember! *store* :test "用户喜欢 Clojure 和 Next.js")]
      (is (= "用户喜欢 Clojure 和 Next.js" (ms/recall *store* :test id))))))

(deftest recall-nonexistent
  (testing "recall 不存在的 content-id 返回 nil"
    (ms/->memory *store* :test)
    (is (nil? (ms/recall *store* :test "nonexistent-id")))))

(deftest list-content-empty
  (testing "空库 list-content 返回空列表"
    (ms/->memory *store* :test)
    (let [out (ms/list-content *store* :test {:page 1 :page-size 10})]
      (is (= [] (:items out)))
      (is (= 0 (:total-count out))))))

(deftest list-content-with-items
  (testing "有内容时 list-content 无 :q 按时间倒序分页"
    (ms/->memory *store* :test)
    (ms/remember! *store* :test "第一条")
    (ms/remember! *store* :test "第二条")
    (ms/remember! *store* :test "第三条")
    (let [out (ms/list-content *store* :test {:page 1 :page-size 2})]
      (is (= 2 (count (:items out))))
      (is (= 3 (:total-count out)))
      (is (= "第三条" (:content (first (:items out)))))
      (is (= "第二条" (:content (second (:items out))))))))

(deftest list-content-pagination
  (testing "分页 page、page-size 正确"
    (ms/->memory *store* :test)
    (dotimes [_ 5] (ms/remember! *store* :test "item"))
    (let [p1 (ms/list-content *store* :test {:page 1 :page-size 2})
          p2 (ms/list-content *store* :test {:page 2 :page-size 2})]
      (is (= 2 (count (:items p1))))
      (is (= 2 (count (:items p2))))
      (is (= 5 (:total-count p1)))
      (is (= 5 (:total-count p2))))))

(deftest list-content-with-query
  (testing "list-content :q 向量搜索返回相似结果"
    (ms/->memory *store* :test)
    (ms/remember! *store* :test "用户喜欢 Clojure 编程")
    (ms/remember! *store* :test "今天是晴天")
    (ms/remember! *store* :test "Clojure 是函数式语言")
    (let [out (ms/list-content *store* :test {:q "Clojure" :page 1 :page-size 10})]
      (is (<= 1 (:total-count out)))
      (is (<= 1 (count (:items out))) (str "向量搜索应返回结果，out=" out))
      (is (some #(str/includes? (:content %) "Clojure") (:items out))
          (str "结果应含 Clojure，items=" (mapv :content (:items out)))))))

(deftest change-updates-content
  (testing "change! 更新 content 后 recall 返回新内容"
    (ms/->memory *store* :test)
    (let [id (ms/remember! *store* :test "原始内容")]
      (ms/change! *store* :test id "更新后的内容")
      (is (= "更新后的内容" (ms/recall *store* :test id))))))

(deftest forget-removes-content
  (testing "forget! 后 recall 返回 nil"
    (ms/->memory *store* :test)
    (let [id (ms/remember! *store* :test "将被删除")]
      (ms/forget! *store* :test id)
      (is (nil? (ms/recall *store* :test id))))))

(deftest forget-list-excludes-forgotten
  (testing "forget! 后 list-content 不再包含该项"
    (ms/->memory *store* :test)
    (let [id (ms/remember! *store* :test "唯一一条")]
      (ms/forget! *store* :test id)
      (let [out (ms/list-content *store* :test {:page 1 :page-size 10})]
        (is (= 0 (count (:items out))))
        (is (= 0 (:total-count out)))))))

(deftest key-isolation
  (testing "不同 key 的数据彼此隔离"
    (ms/->memory *store* :key-a)
    (ms/->memory *store* :key-b)
    (let [id-a (ms/remember! *store* :key-a "A 的数据")]
      (is (= "A 的数据" (ms/recall *store* :key-a id-a)))
      (is (nil? (ms/recall *store* :key-b id-a))))
    (let [id-b (ms/remember! *store* :key-b "B 的数据")]
      (is (= "B 的数据" (ms/recall *store* :key-b id-b)))
      (is (nil? (ms/recall *store* :key-a id-b))))))

(deftest remember-requires-vec-path
  (testing "无 vec-extension-path 时 remember! 抛出"
    (let [store-no-vec (ms/->memory-store {:base-dir *base-dir*
                                          :embed-fn embed-fn})]
      (try
        (ms/remember! store-no-vec :test "x")
        (is false "should have thrown")
        (catch Exception e
          (is (re-find #"vec-extension-path" (str (ex-message e)))))))))

(deftest embed-fn-wrong-dimension
  (testing "embed-fn 返回维度不符时 remember! 抛出"
    (let [bad-embed-fn (fn [_] [0.1 0.2])
          store-bad (ms/->memory-store {:base-dir *base-dir*
                                        :embed-fn bad-embed-fn
                                        :vec-extension-path *vec-path*
                                        :embed-dim embed-dim})]
      (ms/->memory store-bad :test)
      (try
        (ms/remember! store-bad :test "x")
        (is false "should have thrown")
        (catch Exception e
          (is (re-find #"embed-dim" (str (ex-message e)))))))))

(deftest list-content-search-requires-vec
  (testing "有 :q 无 vec-extension-path 时 list-content 抛出"
    (let [store-no-vec (ms/->memory-store {:base-dir *base-dir*
                                           :embed-fn embed-fn})]
      (try
        (ms/list-content store-no-vec :test {:q "x"})
        (is false "should have thrown")
        (catch Exception e
          (is (or (re-find #"vec-extension-path" (str (ex-message e)))
                  (re-find #"Vector search" (str (ex-message e))))))))))
