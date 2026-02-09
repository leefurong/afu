(ns agent.tools.execute-clojure.sci-sandbox.script.add-ma-to-cache
  "脚本：对缓存中每只股票的日 K（payload 非占位符）计算 MA5/10/20/30/60 与金叉/死叉，写回 DB。
   股票列表来自 k_line_daily 表内已有 ts_code，不依赖 stock-list-store。
   运行：clj -M:add-ma（在 backend 目录下），或 REPL 中 (require '...) (run-add-ma!)."
  (:require [agent.tools.execute-clojure.sci-sandbox.k-line-store :as k-line-store])
  (:gen-class))

(defn- parse-close [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch NumberFormatException _ nil))
    :else nil))

(defn- simple-moving-average
  "与 stock 中逻辑一致：closes 为 vector，返回等长 vector，前 (dec days) 个为 nil。"
  [closes days]
  (let [n (count closes)
        d (long days)]
    (mapv (fn [i]
            (if (< i (dec d))
              nil
              (let [seg (subvec closes (inc (- i d)) (inc i))
                    s (reduce + 0.0 seg)]
                (/ s d))))
          (range n))))

(defn- cross-type-at
  "根据 ma-short、ma-long 两列在位置 i 的值，以及 i-1 的值，返回 \"金叉\" \"死叉\" \"无\"。"
  [ma-short ma-long i]
  (if (< i 1)
    "无"
    (let [prev-s (nth ma-short (dec i))
          prev-l (nth ma-long (dec i))
          curr-s (nth ma-short i)
          curr-l (nth ma-long i)]
      (if (or (nil? prev-s) (nil? prev-l) (nil? curr-s) (nil? curr-l))
        "无"
        (cond
          (and (<= prev-s prev-l) (> curr-s curr-l)) "金叉"
          (and (>= prev-s prev-l) (< curr-s curr-l)) "死叉"
          :else "无")))))

(defn- payload-for-db
  "从带 :ts_code :trade_date :source 的 row 得到要写入 row_payload 的 map（不含这三键）。"
  [row]
  (dissoc row :ts_code :trade_date :source))

(defn process-one-stock!
  "对单只股票：仅从缓存拉取全部有效 K 线（payload 非 -1，升序），计算 MA 与 cross_type，写回 DB。返回处理的条数。"
  [ts-code]
  (let [bounds (k-line-store/get-cache-bounds ts-code)]
    (if-not bounds
      0
      (let [left  (:left bounds)
            right (:right bounds)
            rows  (k-line-store/get-cached-rows-for-code ts-code left right)]
        (if (empty? rows)
          0
          (let [rows-v    (vec rows)
                closes    (mapv (fn [r] (parse-close (:close r))) rows-v)
                ma5       (simple-moving-average closes 5)
                ma10      (simple-moving-average closes 10)
                ma20      (simple-moving-average closes 20)
                ma30      (simple-moving-average closes 30)
                ma60      (simple-moving-average closes 60)
                n         (count rows-v)]
            (doseq [i (range n)]
              (let [row (nth rows-v i)
                    ct (cross-type-at ma5 ma20 i)
                    enriched (assoc row
                                   :ma5 (nth ma5 i)
                                   :ma10 (nth ma10 i)
                                   :ma20 (nth ma20 i)
                                   :ma30 (nth ma30 i)
                                   :ma60 (nth ma60 i)
                                   :cross_type ct)
                    payload (payload-for-db enriched)]
                (k-line-store/update-daily-row-mas! ts-code (:trade_date row) payload ct)))
            n))))))

(defn run-add-ma!
  "主流程：确保 schema（含 cross_type 迁移）、从缓存表取全部 ts_code，逐只处理并 println 进展。"
  []
  (k-line-store/ensure-schema! nil)
  (let [codes (k-line-store/get-all-ts-codes-in-cache)
        total (count codes)]
    (if (empty? codes)
      (do (println "[add-ma-to-cache] 缓存中无股票代码。")
          (flush))
      (do
        (println (str "[add-ma-to-cache] 开始处理 " total " 只股票，按代码顺序逐只计算 MA 并写回。"))
        (flush)
        (loop [i 0 processed 0 codes codes]
          (if (empty? codes)
            (do (println (str "[add-ma-to-cache] 完成。共处理 " processed " 条 K 线。"))
                (flush))
            (let [code (first codes)
                  n    (process-one-stock! code)]
              (when (pos? (inc i))
                (when (or (zero? (mod (inc i) 50)) (= (inc i) total))
                  (println (str "[add-ma-to-cache] 进度 " (inc i) " / " total "，当前 " code " 本只 " n " 条。"))
                  (flush)))
              (recur (inc i) (+ processed n) (rest codes)))))))))

(defn -main [& _]
  (run-add-ma!))
