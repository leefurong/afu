(ns agent.tools.tushare-daily.spec
  "tushare_daily 工具说明与参数 schema。")

(defn tool-spec
  "返回本工具的完整 spec map（:name :description :parameters），供 tool_loader 组装 API 用。"
  []
  {:name "tushare_daily"
   :description (str "获取 A 股日线行情（未复权）。数据来源：Tushare daily 接口，交易日每天 15～16 点入库，停牌期间无数据。"
                    " 日期格式均为 YYYYMMDD，如 20180701。"
                    " 可按股票代码+日期区间查询，或按单日 trade_date 查询当日股票。")
   :parameters {:type "object"
                :properties {:ts_code    {:type "string"
                                          :description "股票代码，支持多个用逗号分隔，如 000001.SZ,600000.SH"}
                             :trade_date {:type "string"
                                          :description "交易日期 YYYYMMDD，查询单日股票"}
                             :start_date {:type "string"
                                          :description "开始日期 YYYYMMDD"}
                             :end_date   {:type "string"
                                          :description "结束日期 YYYYMMDD"}}
                :required []}})
