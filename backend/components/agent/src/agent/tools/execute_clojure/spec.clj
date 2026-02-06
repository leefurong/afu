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
                       "   - K 线：(stock/get-k stock-code dwmsy beg-date count)。stock-code 如 \"000001\"；dwmsy \"日k\"|\"周k\"|\"月k\"；beg-date YYYYMMDD；count 可选、默认 20。返回 {:ok {:fields _ :items _}} 或 {:error _}。\n"
                       "   - 移动平均 (stock/ma stock-code days & 可选 beg-date bar-count)。days 为周期 5/10/20/60；beg-date 起始日 YYYYMMDD；bar-count 取 K 线条数、默认 250。返回 {:ok {:fields [\"trade_date\" \"close\" \"maN\"] :items [[date close ma] ...]}}，items 升序、最后一行即最近交易日。\n"
                       "     用法：今天 MA5 → (stock/ma \"000001\" 5)；从某日起 → (stock/ma \"000001\" 5 \"YYYYMMDD\")；只要最近 2 条 → (stock/ma \"000001\" 5 \"YYYYMMDD\" 2)。\n"
                       "   - 金叉 (stock/golden-cross stock-code short-days long-days & 可选 beg-date bar-count)。短期均线上穿长期均线。返回 {:ok {:crosses [{:date \"YYYYMMDD\" :short_ma x :long_ma y} ...]}} 或 {:error _}。\n"
                       "6) 如需其他外部数据，可用 http + json + env：例如 Tushare 可 POST http://api.tushare.pro，body 为 JSON（api_name、token、params），返回用 json/parse-string 解析。\n"
                       "7) pprint：(pprint/pprint x) 格式化打印，便于查看复杂结构。\n\n"
                       "禁止：require/import、Java 互操作、eval/load-file/slurp/spit/read-string 等。\n\n"
                       "入参为 code（字符串）。返回 {:ok 结果} 或 {:error 错误信息}，可能带 :out。")
     :parameters {:type "object"
                  :properties {:code {:type "string"
                                     :description (str "要执行的 Clojure (SCI) 代码。仅限 SCI 子集：clojure.core 常用函数 + http/json/env/stock/pprint，不可 require、不可 Java 互操作。环境变量名仅限：" wl "。")}}
                  :required ["code"]}}))
