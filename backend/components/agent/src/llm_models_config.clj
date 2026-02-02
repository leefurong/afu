(ns llm-models-config
  "LLM 模型配置：按 provider + model 返回 make-client 可用的 opts。
  对外仅暴露 (get-model-opts provider-name model-name)。")

;; ---------------------------------------------------------------------------
;; 配置数据：provider -> model -> opts（:base-url :api-key-env 在 provider 层）
;; ---------------------------------------------------------------------------

(def ^:private provider-defaults
  {:moonshot {:base-url "https://api.moonshot.cn/v1"
              :api-key-env "MOONSHOT_API_KEY"}
   :openai   {:base-url "https://api.openai.com/v1"
              :api-key-env "OPENAI_API_KEY"}
   :openai-compatible {:base-url (or (System/getenv "LLM_BASE_URL") "http://localhost:8080/v1")
                       :api-key-env "LLM_API_KEY"}})

(def ^:private model-opts
  {:moonshot
   {:kimi-k2-turbo-preview {:model "kimi-k2-turbo-preview" :temperature 0.7 :max-tokens 4096}
    :kimi-k2.5             {:model "kimi-k2.5" :temperature 0.7 :max-tokens 8192}
    :moonshot-v1           {:model "moonshot-v1-8k" :temperature 0.7 :max-tokens 4096}}

   :openai
   {:gpt-4o                 {:model "gpt-4o" :temperature 0.7 :max-tokens 4096}
    :gpt-4o-mini            {:model "gpt-4o-mini" :temperature 0.7 :max-tokens 4096}
    :gpt-4-turbo            {:model "gpt-4-turbo" :temperature 0.7 :max-tokens 4096}}

   :openai-compatible
   {:default {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini") :temperature 0.7 :max-tokens 4096}}})

;; ---------------------------------------------------------------------------
;; 对外接口
;; ---------------------------------------------------------------------------

(defn get-model-opts
  "根据 provider-name 与 model-name 返回可用于 llm/make-client 的 opts。
  provider-name、model-name 可为 keyword 或 string（会转成 keyword 查找）。
  返回 map 含 :base-url :model :api-key :temperature :max-tokens（:api-key 已从
  该 provider 的 :api-key-env 环境变量解析）。若 provider 或 model 不存在，返回 nil。"
  [provider-name model-name]
  (let [pk (keyword (name provider-name))
        mk (keyword (name model-name))
        prov (get provider-defaults pk)
        model-cfg (get-in model-opts [pk mk])]
    (when (and prov model-cfg)
      (merge
       {:base-url (:base-url prov)
        :api-key  (System/getenv (:api-key-env prov))}
       model-cfg))))
