(ns agent.tools.registry
  "工具约定与注册表。一个 tool = handler + call-display + spec，通过 Tool 协议统一。")

(defprotocol Tool
  "一个工具的约定：执行、展示、说明。每工具实现此 protocol（如 defrecord + extend）。"
  (handle [this args ctx]
    "执行。args 为解析后的参数 map，ctx 为调用方传入的可选上下文（如 {:conversation-id uuid}），工具自行解读。返回 {:ok v} 或 {:error \"...\"}。")
  (call-display [this args]
    "本次调用用于展示的 map，会与 {:name :arguments} 合并后作为 :tool-call 事件 payload；无需额外展示则返回 {}。")
  (spec [this]
    "返回 {:name :description :parameters}，供 API 组装为 OpenAI/Moonshot 工具定义。"))

(def ^:private tools (atom {}))

(defn register!
  "注册工具。tool 须实现 Tool protocol（如 (defrecord X [] Tool ...) 的实例）。"
  [tool-name tool]
  (swap! tools assoc (str tool-name) tool)
  nil)

(defn get-tool
  "根据工具名返回已注册的 tool 实例，未注册返回 nil。调用方直接 (handle tool args ctx) / (call-display tool args) / (spec tool)。"
  [tool-name]
  (get @tools (str tool-name)))

(defn registered-names
  []
  (vec (keys @tools)))
