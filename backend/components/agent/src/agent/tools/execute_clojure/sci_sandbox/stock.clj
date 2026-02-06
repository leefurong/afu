(ns agent.tools.execute-clojure.sci-sandbox.stock
  "SCI 沙箱内可调用的 K 线数据：get-k，基于 Tushare。"
  (:require [agent.tools.execute-clojure.sci-sandbox.env :as env]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as str])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(def ^:private api-url "http://api.tushare.pro")
(def ^:private timeout-ms 15000)
(def ^:private basic-iso DateTimeFormatter/BASIC_ISO_DATE)

(defn- parse-ymd
  "将 YYYYMMDD 字符串转为 LocalDate。"
  [s]
  (when (and (string? s) (= 8 (count s)))
    (try
      (LocalDate/parse s basic-iso)
      (catch Exception _ nil))))

(defn- date->str
  [^LocalDate d]
  (.format d basic-iso))

(defn- plus-days
  [^LocalDate d n]
  (.plus d n ChronoUnit/DAYS))

(defn- minus-days
  [^LocalDate d n]
  (.minus d n ChronoUnit/DAYS))

(defn- next-weekday
  "若 d 为周六/周日则顺延到下一周一；否则返回 d。"
  [^LocalDate d]
  (case (.getValue (.getDayOfWeek d))
    6 (plus-days d 2)   ;; Saturday -> Monday
    7 (plus-days d 1)   ;; Sunday -> Monday
    d))

(defn- start-date-from-beg
  "起始日：beg-date 若为周末则顺延到下一交易日（周一）。"
  [beg-date]
  (when-let [d (parse-ymd beg-date)]
    (date->str (next-weekday d))))

(defn- end-date-from-beg-and-count
  "根据起始日与需要的 K 线条数计算 end_date，保证区间内至少有 count 根 K 线。"
  [beg-date count dwmsy]
  (when-let [start-d (parse-ymd beg-date)]
    (let [start-d (next-weekday start-d)
          days    (case dwmsy
                    "日k" (* count 2)   ;; 约 count 个交易日
                    "周k" (* count 7)
                    "月k" (* (max 1 count) 31)
                    31)]
      (date->str (plus-days start-d (max 1 days))))))

(defn- dwmsy->api
  "K 线类型 -> [api_name, params 中的 freq]。日k 用 daily 无 freq；周/月用 stk_weekly_monthly。"
  [dwmsy]
  (case dwmsy
    "日k" [:daily nil]
    "周k" [:stk_weekly_monthly "week"]
    "月k" [:stk_weekly_monthly "month"]
    "季k" [:unsupported nil]
    "年k" [:unsupported nil]
    nil))

(defn- normalize-ts-code
  "若用户只传 000001，补全为 000001.SZ（深市）或 000001.SH（沪市）。简单规则：6 开头为沪，否则为深。"
  [s]
  (cond
    (str/blank? s) s
    (str/includes? (str s) ".") (str s)
    (str/starts-with? (str s) "6") (str s ".SH")
    :else (str s ".SZ")))

(defn- request-tushare
  [token api-name params]
  (let [body (json/generate-string {:api_name api-name
                                    :token    token
                                    :params   params})]
    (try
      (let [resp (client/post api-url
                             {:body            body
                              :content-type    :json
                              :accept          :json
                              :socket-timeout  timeout-ms
                              :conn-timeout    timeout-ms})
            data (json/parse-string (:body resp) true)]
        (if (contains? data :code)
          (let [code (long (:code data))]
            (if (zero? code)
              (let [inner (or (:data data) data)
                    payload (-> (select-keys inner [:fields :items])
                                (update-vals #(or % [])))
                    items (:items payload)]
                {:ok (assoc payload :items (or items []))})
              {:error (str "Tushare 返回错误: " (or (:msg data) "未知") " (code=" code ")")}))
          {:error "Tushare 返回格式异常"}))
      (catch Exception e
        {:error (str "请求 Tushare 失败: " (.getMessage e))}))))

(defn get-k
  "获取 K 线数据。
   参数:
   - stock-code: 股票代码，如 \"000001\" 或 \"000001.SZ\"
   - dwmsy: K 线类型，\"日k\" \"周k\" \"月k\" \"季k\" \"年k\"（季k/年k 暂不支持）
   - beg-date: 起始日期，格式 YYYYMMDD；若该日未开盘（如周末）则顺延到下一交易日
   - count: 需要的 K 线条数，可选，默认 20；end_date 据此自动计算
   返回 {:ok {:fields [...] :items [[...] ...]}} 或 {:error \"...\"}，items 最多 count 条（从起始日起）。"
  ([stock-code dwmsy beg-date]
   (get-k stock-code dwmsy beg-date 20))
  ([stock-code dwmsy beg-date count]
   (let [count (if (or (nil? count) (neg? (long count))) 20 (long count))
         token (env/get-env "TUSHARE_API_TOKEN")]
     (if (str/blank? token)
       {:error "未配置 TUSHARE_API_TOKEN，请在环境中设置"}
       (let [[api-name freq] (dwmsy->api dwmsy)]
         (cond
           (= api-name :unsupported)
           {:error "暂不支持季k/年k，请使用 日k、周k 或 月k。"}

           (not (parse-ymd beg-date))
           {:error "起始日期格式须为 YYYYMMDD，例如 20250101。"}

           :else
           (let [ts-code   (normalize-ts-code (str stock-code))
                 start-d   (start-date-from-beg beg-date)
                 end-d     (end-date-from-beg-and-count beg-date count dwmsy)
                 params    (cond-> {:ts_code    ts-code
                                   :start_date start-d
                                   :end_date   end-d}
                            freq (assoc :freq freq))
                 result    (request-tushare token (name api-name) params)]
             (if (:error result)
               result
               (update result :ok
                       (fn [payload]
                         ;; Tushare 返回按日期降序；取「从起始日起」的 count 条，仍按日期降序返回
                         (update payload :items
                                 (fn [items]
                                   (let [items (or items [])]
                                     (reverse (take count (reverse items))))))))))))))))

(defn- field-index
  [fields name]
  (first (keep-indexed (fn [i f] (when (= (str f) (str name)) i)) fields)))

(defn- parse-close
  [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch NumberFormatException _ nil))
    :else nil))

(defn- simple-moving-average
  "计算 closes 的 days 日简单移动平均，返回与 closes 等长的序列，前 (dec days) 个为 nil。"
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

(defn- row-date-str
  "取 row 的 trade_date 并转为可比较的 YYYYMMDD 字符串。"
  [row date-idx]
  (str (nth row date-idx nil)))

(defn ma
  "计算日 K 收盘价的 N 日简单移动平均。语义：截止日 till-date，往前取 back-days 个交易日。
   - stock-code: 股票代码；days: MA 周期（如 5、10、20、60）。
   - till-date: 截止日 YYYYMMDD（含）；不传则用「最近交易日」。
   - back-days: 从 till-date 往前取多少个交易日，默认 1（就取截止日当天）。
   用法：今天 MA5 → (ma \"000001\" 5)；某日当天 → (ma \"000001\" 5 \"20260206\")；某日及前 4 天 → (ma \"000001\" 5 \"20260206\" 5)。
   返回 {:ok {:items [{:trade_date \"...\" :close x :maN y} ...]}} 按日期升序（最早一天在前）。"
  ([stock-code days]
   (ma stock-code days nil 1))
  ([stock-code days till-date]
   (ma stock-code days till-date 1))
  ([stock-code days till-date back-days]
   (let [ma-days    (long days)
         back-days  (if (or (nil? back-days) (neg? (long back-days))) 1 (long back-days))]
     (cond
       (< ma-days 2) {:error "MA 周期 days 至少为 2。"}
       (and till-date (not (parse-ymd till-date))) {:error "截止日格式须为 YYYYMMDD。"}
       :else
       (let [;; 需要 (ma-days-1 + back-days) 根 K，且区间要覆盖到 till-date（或最近）
             k-need    (+ (dec ma-days) back-days)
             k-request (max 320 (+ k-need 150))
             ;; 若未传 till-date，先拉近期数据，用最后一条的日期作为截止日
             start-d   (if till-date
                         (when-let [d (parse-ymd till-date)]
                           (date->str (minus-days (next-weekday d) (* 2 k-request))))
                         (date->str (minus-days (LocalDate/now) 400)))
             k         (get-k (str stock-code) "日k" start-d k-request)]
         (cond
           (:error k) k
           (nil? start-d) {:error "截止日格式须为 YYYYMMDD。"}
           :else
           (let [payload   (:ok k)
                 fields    (:fields payload)
                 items-raw (or (:items payload) [])
                 items-asc (vec (reverse items-raw))
                 date-idx  (field-index fields "trade_date")
                 close-idx (field-index fields "close")]
             (if (or (nil? date-idx) (nil? close-idx))
               {:error "K 线数据缺少 trade_date 或 close 字段"}
               (let [;; 确定截止日：若未传则用数据里最后一天（最近交易日）
                     till-str (if (str/blank? (str till-date))
                                (row-date-str (peek items-asc) date-idx)
                                (str till-date))
                     ;; 最后一个 date <= till-str 的下标
                     i-end    (last (keep-indexed (fn [i row]
                                                    (when (<= (compare (row-date-str row date-idx) till-str) 0) i))
                                                  items-asc))
                     slice-len (+ (dec ma-days) back-days)
                     i-start  (when (and (some? i-end) (>= i-end (dec slice-len)))
                                (max 0 (- i-end slice-len -1)))
                     slice    (when (and (some? i-start) (some? i-end))
                                (subvec items-asc i-start (inc i-end)))]
                 (if (or (nil? slice) (< (count slice) ma-days))
                   {:error (str "无法在截止日 " (or till-str till-date) " 前取到 " back-days " 天数据。")}
                   (let [closes    (mapv (fn [row] (parse-close (nth row close-idx nil))) slice)
                         ma-vals   (simple-moving-average closes ma-days)
                         ma-kw     (keyword (str "ma" ma-days))
                         ;; slice 前 (ma-days-1) 行为前置，最后 back-days 行为输出
                         out-start (- (count slice) back-days)
                         out-items (mapv (fn [i]
                                           (let [row (nth slice i)
                                                 d   (nth row date-idx)
                                                 c   (nth closes i)
                                                 m   (nth ma-vals i)]
                                             {:trade_date (str d) :close c ma-kw m}))
                                         (vec (range out-start (count slice))))]
                     {:ok {:items out-items}})))))))))))

(defn golden-cross
  "检测短期均线上穿长期均线（金叉）。语义与 ma 一致：截止日 till-date，往前 back-days 天。
   参数: stock-code, short-days, long-days；可选 till-date（默认最近交易日）、back-days（默认 5）。
   返回 {:ok {:crosses [{:date \"YYYYMMDD\" :short_ma x :long_ma y} ...]}} 或 {:error _}。"
  ([stock-code short-days long-days]
   (golden-cross stock-code short-days long-days nil 5))
  ([stock-code short-days long-days till-date]
   (golden-cross stock-code short-days long-days till-date 5))
  ([stock-code short-days long-days till-date back-days]
   (cond
     (< (long short-days) 2) {:error "短期 MA 周期至少为 2。"}
     (< (long long-days) 2) {:error "长期 MA 周期至少为 2。"}
     (>= (long short-days) (long long-days)) {:error "短期周期应小于长期周期（如 5 与 20）。"}
     :else
     (let [ma-short (ma (str stock-code) (long short-days) till-date back-days)
           ma-long  (ma (str stock-code) (long long-days) till-date back-days)]
       (cond
         (:error ma-short) ma-short
         (:error ma-long)  ma-long
         :else
         (let [short-items (get-in ma-short [:ok :items])
               long-items  (get-in ma-long [:ok :items])
               skw        (keyword (str "ma" short-days))
               lkw        (keyword (str "ma" long-days))
               n          (min (count short-items) (count long-items))]
           (if (< n 2)
             {:ok {:crosses []}}
             (let [crosses (into []
                                 (comp
                                  (map (fn [i]
                                         (let [prev-s (get (nth short-items (dec i)) skw)
                                               prev-l (get (nth long-items (dec i)) lkw)
                                               curr-s (get (nth short-items i) skw)
                                               curr-l (get (nth long-items i) lkw)
                                               d      (:trade_date (nth short-items i))]
                                           (when (and (number? prev-s) (number? prev-l) (number? curr-s) (number? curr-l)
                                                      (<= prev-s prev-l) (> curr-s curr-l))
                                             {:date d :short_ma curr-s :long_ma curr-l}))))
                                  (filter some?))
                                 (range 1 n))]
               {:ok {:crosses crosses}}))))))))
