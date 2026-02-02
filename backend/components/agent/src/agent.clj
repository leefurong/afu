(ns agent
  (:require [memory :refer [->memory]]
            [clojure.core.async :as a]))

(defn ->agent
  ([gene] (->agent gene (->memory))) ;; 默认空记忆
  ([gene memory] {:gene gene :memory memory}))

(def ^:private mock-chunks
  ["你好" "！" "我是" "阿福" "，" "很高兴" "见到" "你" "。"])

(defn chat
  "发送消息给 agent，返回一个 ch。
   ch 中事件：[:thinking \"...\"] [:content \"...\"] ... [:done new-agent]"
  [agent message]
  (let [ch (a/chan 8)]
    (a/go
      (try
        (a/>! ch [:thinking "思考中..."])
        (doseq [s mock-chunks]
          (a/>! ch [:content s])
          (a/<! (a/timeout 100)))
        (a/>! ch [:done agent])
        (catch Exception e
          (a/>! ch [:done agent]))
        (finally
          (a/close! ch))))
    ch)) 