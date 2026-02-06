(ns agent.tools.execute-clojure.spec
  "execute_clojure 工具说明与参数 schema，由本工具自行依赖 env 等，不暴露给 tool_loader。"
  (:require [agent.tools.execute-clojure.env :as env]
            [clojure.string :as str]))

(defn- env-whitelist-str []
  (str/join "、" (sort env/env-whitelist)))

(defn tool-spec
  "返回本工具的完整 spec map（:name :description :parameters），供 tool_loader 组装 API 用。
  说明字符串在此处根据 env 等内部状态构建，tool_loader 无需知晓任何占位或 vars。"
  []
  (let [wl (env-whitelist-str)]
    {:name "execute_clojure"
     :description (str "在 SCI（Small Clojure Interpreter）沙箱中执行一段 Clojure 代码并返回结果。注意：这里是 Clojure (SCI) 子集，不是完整 Clojure。\n\n"
                       "可用范围：\n"
                       "1) SCI 提供的 clojure.core（无 Java 互操作、无 require/import）；\n"
                       "2) 沙箱内置的 http 命名空间：(http/get \"url\")、(http/post \"url\" {:body \"...\" :headers {...}})，返回 {:status N :headers {...} :body \"...\"} 或 {:error \"...\"}；\n"
                       "3) json：(json/parse-string \"...\")、(json/write-str {...})；\n"
                       "4) env：(env/get-env \"VAR_NAME\") 读取环境变量，仅可读取以下环境变量：" wl "，其他名称返回 nil；\n"
                       "5) 如需访问外部数据， 请阅读这块补充说明（结合上述 http + json + env 使用）：\n"
                       "   · 股票数据：如果使用Tushare, 需 env TUSHARE_API_TOKEN。POST http://api.tushare.pro，body 为 JSON（api_name、token、params），返回用 json/parse-string 解析。\n\n"
                       "禁止：require/import、Java 互操作、eval/load-file/slurp/spit/read-string 等。\n\n"
                       "入参为 code（字符串）。返回 {:ok 结果} 或 {:error 错误信息}，可能带 :out。")
     :parameters {:type "object"
                  :properties {:code {:type "string"
                                     :description (str "要执行的 Clojure (SCI) 代码。仅限 SCI 子集：clojure.core 常用函数 + http/json/env，不可 require、不可 Java 互操作。环境变量名仅限：" wl "。")}}
                  :required ["code"]}}))
