(ns agent.tools.execute-clojure.json
  "供 execute_clojure 沙箱内使用的 JSON 能力：parse-string、write-str，基于 cheshire。"
  (:require [cheshire.core :as cheshire]))

(defn parse-string
  "将 JSON 字符串解析为 Clojure 数据。keywordize? 为 true 时键转为 keyword。"
  ([s]
   (cheshire/parse-string (str s)))
  ([s keywordize?]
   (cheshire/parse-string (str s) keywordize?)))

(defn write-str
  "将 Clojure 数据序列化为 JSON 字符串。"
  [x]
  (cheshire/generate-string x))
