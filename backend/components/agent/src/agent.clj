(ns agent)
(require '[memory :refer [->memory]])

(defn ->agent
  ([gene] (->agent gene (->memory))) ;; 默认空记忆
  ([gene memory] {:gene gene :memory memory}))

(defn chat
  "发送消息给agent，返回一个ch。
   这个ch的内容结构：
   [:thinking \"正在思考...\"] [:content \"你好\"] [:content \"世界\"] [:done new-agent-struct]"
  [agent message]) 