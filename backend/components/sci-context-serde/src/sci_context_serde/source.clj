(ns sci-context-serde.source
  "从「最近一条 assistant 代码」中解析出形式，并查找给定符号的定义形式。纯函数，可独立测试。"
  (:require [clojure.string :as str]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]))

(defn read-forms
  "从代码字符串中依次读取所有形式，返回 vector。读到底或异常时结束。"
  [code-str]
  (when (and code-str (not (str/blank? (str code-str))))
    (let [rdr (rt/push-back-reader (str code-str))]
      (loop [acc []]
        (let [form (try (tr/read rdr nil ::eof)
                        (catch Exception _ ::eof))]
          (if (or (nil? form) (identical? form ::eof))
            acc
            (recur (conj acc form))))))))

(defn defining-form?
  "判定 form 是否为「定义」：def 或 defn，且第一个参数为符号（绑定名）。"
  [form]
  (and (seq? form)
       (>= (count form) 2)
       (#{'def 'defn} (first form))
       (symbol? (second form))))

(defn defining-symbol
  "若 form 为 (def name ...) 或 (defn name ...)，返回 name，否则 nil。"
  [form]
  (when (defining-form? form)
    (second form)))

(defn defining-forms-by-name
  "从形式列表中收集「定义」的 符号 -> 形式 映射。
   同一符号多次出现时，后者覆盖前者（与执行顺序一致）。"
  [forms]
  (reduce (fn [m form]
            (if-let [sym (defining-symbol form)]
              (assoc m sym form)
              m))
          {}
          forms))

(defn find-source
  "在形式列表 forms 中查找符号 sym 的定义形式；若有则返回 (pr-str form)，否则 nil。
   forms 通常由 (read-forms recent-code) 得到。"
  [sym forms]
  (when (and (symbol? sym) (seq forms))
    (when-let [form (get (defining-forms-by-name forms) sym)]
      (pr-str form))))
