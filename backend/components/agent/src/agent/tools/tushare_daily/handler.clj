(ns agent.tools.tushare-daily.handler
  "tushare_daily 工具：调用 Tushare A股日线行情接口。实现 Tool 协议。"
  (:require [agent.tools.tushare-daily.api :as api]
            [agent.tools.tushare-daily.spec :as spec]
            [agent.tools.registry :as registry]
            [clojure.string :as str]))

(defn- get-token []
  (System/getenv "TUSHARE_API_TOKEN"))

(defrecord TushareDailyTool []
  registry/Tool
  (handle [_ args _ctx]
    (let [token (get-token)
          args  (let [m (select-keys args [:ts_code :trade_date :start_date :end_date])
                      m (update-vals m #(when (string? %) (str/trim %)))]
                  (into {} (remove (comp nil? second) m)))]
      (api/daily token args)))
  (call-display [_ args]
    {:code (str "股票代码: " (:ts_code args) "\n"
                "交易日期: "(:trade_date args) "\n"
                "日期范围: "(:start_date args) " - "(:end_date args))})
  (spec [_]
    (spec/tool-spec)))

(registry/register! "tushare_daily" (->TushareDailyTool))
