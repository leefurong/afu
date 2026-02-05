(ns llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as a]
            [llm :refer [make-client stream-chat complete execute-clojure-tool parse-tool-arguments]]))

;; 以下测试假设 llm 已正确实现，测的是「正常可工作的行为」。
;; 需配置 MOONSHOT_API_KEY（Kimi/月之暗面）等环境变量才能通过。

(def ^:private config
  "Moonshot/Kimi 基本配置，全文件复用。"
  {:api-key (System/getenv "MOONSHOT_API_KEY")
   :base-url "https://api.moonshot.cn/v1"
   :model "kimi-k2-turbo-preview"})

(def ^:private config-multi-modal
  "Moonshot/Kimi 多模态配置"
  {:api-key (System/getenv "MOONSHOT_API_KEY")
   :base-url "https://api.moonshot.cn/v1"
   :model "kimi-k2.5"})

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

(deftest complete-with-tools-accepts-tools-and-returns-valid-message
  (testing "带 :tools 调用 complete 时请求合法，返回含 :role :content，或有 :tool_calls"
    (let [client (make-client config)
          messages [{:role "user" :content "请用你手上的工具计算 (reduce + (range 10))，只调用工具不要猜结果。"}]
          out (complete client messages {:tools [execute-clojure-tool]})]
      (is (map? out))
      (is (= "assistant" (:role out)))
      (is (contains? out :content))
      (when (seq (:tool_calls out))
        (doseq [tc (:tool_calls out)]
          (is (contains? tc :id))
          (is (contains? tc :function))
          (is (string? (get-in tc [:function :arguments]))))))))

(deftest parse-tool-arguments-parses-json
  (testing "parse-tool-arguments 能解析合法 JSON 字符串"
    (is (= {:code "(+ 1 2)"} (parse-tool-arguments "{\"code\": \"(+ 1 2)\"}")))
  (testing "空或非法 JSON 返回 nil"
    (is (nil? (parse-tool-arguments "")))
    (is (nil? (parse-tool-arguments "not json"))))))

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
    (let [client (make-client config-multi-modal)
          messages [{:role "user"
                     :content [{:type "text" :text "描述这张图"}
                               ;; Kimi 仅支持 data:image/xxx;base64,... 内联图，不支持外链 URL
{:type "image_url" :image_url {:url "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="}}]}]
          opts {}]
      (let [ch (stream-chat client messages opts)]
        (is (some? ch))
        (a/close! ch))
      (let [out (complete client messages opts)]
        (is (= "assistant" (:role out)))
        (is (contains? out :content))))))
