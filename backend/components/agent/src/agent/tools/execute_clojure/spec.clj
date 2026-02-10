(ns agent.tools.execute-clojure.spec
  "execute_clojure 工具说明与参数 schema，由本工具自行依赖 env 等，不暴露给 tool_loader。"
  (:require [agent.tools.execute-clojure.sci-sandbox :as sci-sandbox]
            [clojure.string :as str]))

(defn- env-whitelist-str []
  (str/join "、" (sort sci-sandbox/env-whitelist)))

(defn tool-spec
  "返回本工具的完整 spec map（:name :description :parameters），供 tool_loader 组装 API 用。
  说明字符串在此处根据 env 等内部状态构建，tool_loader 无需知晓任何占位或 vars。"
  []
  (let [wl (env-whitelist-str)]
    {:name "execute_clojure"
     :description (str "在 SCI（Small Clojure Interpreter）沙箱中执行一段 Clojure 代码并返回结果。注意：这里是 Clojure (SCI) 子集，不是完整 Clojure。\n\n"
                       "* 支持repl驱动编程， 同一会话内多次调用共享执行环境：def、defn 等定义会保留，可先执行一段定义再在后续调用中使用，像 REPL 一样渐进式编程、小步试跑。\n\n"
                       "* SCI沙箱可用功能：\n"
                       "1) SCI 提供的 clojure.core（无 Java 互操作、无 require/import）；\n"
                       "2) 沙箱内置的 http 命名空间：(http/get \"url\")、(http/post \"url\" {:body \"...\" :headers {...}})，返回 {:status N :headers {...} :body \"...\"} 或 {:error \"...\"}；\n"
                       "3) json：(json/parse-string \"...\")、(json/write-str {...})；\n"
                       "4) env：(env/get-env \"VAR_NAME\") 读取环境变量，仅可读取以下环境变量：" wl "，其他名称返回 nil；\n"
                       "5) stock：\n"
                       "   - 全量代码 (stock/all-stock-codes)：返回 A 股所有股票代码的序列（如 \"000001.SZ\" \"600000.SH\" ...），每日更新。例：先 (stock/all-stock-codes) 得到全量代码，再 (stock/golden-cross-for-multiple-stocks codes 5 20 date-from date-to) 得到 {:ok {:by_ts_code {code {:crosses [...]} ...}}}，按 code 过滤有金叉的。(stock/ma-for-multiple-stocks codes period date-from date-to) 可得区间内 MA。\n"
                       "   - 多股票日 K (stock/get-daily-k-for-multiple-stocks stock-codes date-from date-to)。获取 [date-from, date-to] 区间内多只股票的日 K 线。返回 {:ok {:fields _ :by_ts_code {\"000001.SZ\" [{:trade_date _ :close _ :open _ :high _ :low _ ...} ...]} ...}} 或 {:error _}；未初始化时 :error \"K 线缓存未初始化\"。\n"
                       "   - 多股票 MA (stock/ma-for-multiple-stocks stock-codes period date-from date-to)。计算 [date-from, date-to] 区间内每个交易日的 period 日 MA。3 参数时 (stock-codes period date) 表示单日。返回 {:ok {:by_ts_code {\"000001.SZ\" {:items [...]} ...}}}。\n"
                       "   - 多股票金叉 (stock/golden-cross-for-multiple-stocks stock-codes short-period long-period date-from date-to)。区间内金叉；无交易日则该股票 crosses 为 []。返回 {:ok {:by_ts_code {\"000001.SZ\" {:crosses [...]} ...}}}。\n"
                       "   - 某日金叉/死叉统计 (stock/cross-signals-on-date stock-codes trade-date opts)。仅读 DB 缓存，只返回股票代码 {:ts_code _}，分页每页最多 500 条；opts :page :per-page。返回 {:summary _ :pagination _ :golden_cross _ :death_cross _}。\n"
                       "6) 如需其他外部数据，可用 http + json + env：例如 Tushare 可 POST http://api.tushare.pro，body 为 JSON（api_name、token、params），返回用 json/parse-string 解析。\n"
                       "7) pprint：(pprint/pprint x) 格式化打印，便于查看复杂结构。\n\n"
                       "禁止：require/import、Java 互操作、eval/load-file/slurp/spit/read-string 等。\n\n"
                       "入参为 code（字符串）。返回 {:ok 你的最后一条s-expression的执行结果} 或 {:error 错误信息}，可能带 :out。")
     :parameters {:type "object"
                  :properties {:code {:type "string"
                                     :description (str "要执行的 Clojure (SCI) 代码。仅限 SCI 子集：clojure.core 常用函数 + http/json/env/stock/pprint，不可 require、不可 Java 互操作。环境变量名仅限：" wl "。")}}
                  :required ["code"]}}))
