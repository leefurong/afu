(ns llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [llm :refer [make-client stream-chat complete]]))

;; 以下测试假设 llm 已正确实现，测的是「正常可工作的行为」。
;; 需配置 MOONSHOT_API_KEY（Kimi/月之暗面）等环境变量才能通过。

(def ^:private config
  "Moonshot/Kimi 基本配置，全文件复用。"
  {:api-key (System/getenv "MOONSHOT_API_KEY")
   :base-url "https://api.moonshot.ai/v1"
   :model "kimi-k2-turbo-preview"})

(deftest make-client-returns-client
  (testing "make-client 返回可传给 stream-chat 与 complete 的 client"
    (let [client (make-client config)]
      (is (some? client))
      (is (map? client)))))

(deftest complete-returns-assistant-map-with-non-empty-content
  (testing "complete 返回 {:role \"assistant\" :content ...}，且 content 为非空字符串"
    (let [client (make-client config)
          messages [{:role "user" :content "回复一个字：好"}]
          out (complete client messages {})]
      (is (map? out))
      (is (= "assistant" (:role out)))
      (is (contains? out :content))
      (is (string? (:content out)))
      (is (seq (:content out)) "content 非空"))))

(deftest stream-chat-returns-channel-with-content
  (testing "stream-chat 返回 channel，取完后关闭，且至少有一个内容片段"
    (let [client (make-client config)
          messages [{:role "user" :content "回复一个字：好"}]
          ch (stream-chat client messages {})
          chunks (loop [acc []]
                   (let [v (a/<!! ch)]
                     (if (nil? v)
                       acc
                       (recur (conj acc v)))))]
      (is (some? ch))
      (is (seq chunks) "至少有一个内容片段"))))

(deftest stream-chat-and-complete-accept-multimodal-messages
  (testing "stream-chat 与 complete 接受多模态 messages（:content 为 vector），返回结构合法"
    (let [client (make-client config)
          messages [{:role "user"
                     :content [{:type "text" :text "描述这张图"}
                               {:type "image_url" :image_url {:url "https://example.com/img.png"}}]}]
          opts {}]
      (let [ch (stream-chat client messages opts)]
        (is (some? ch))
        (a/close! ch))
      (let [out (complete client messages opts)]
        (is (= "assistant" (:role out)))
        (is (contains? out :content))))))
