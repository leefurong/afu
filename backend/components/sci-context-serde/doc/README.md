# sci-context-serde

独立组件：对 **SCI (Small Clojure Interpreter) context** 做序列化与反序列化，便于持久化或恢复「当前命名空间绑定」而不依赖代码回放。

## 设计思路

- **序列化**：遍历 context 里指定命名空间的每个绑定，按「类型」选择策略：
  1. **能原生 EDN 序列化**（数字、字符串、map、vector 等）→ 直接 `{:type :data :value v}`。
  2. **不能原生序列化** → 再判断是否为函数、Atom 等：
     - **函数**：从「最近一条 assistant 写的代码」里解析出该符号的 `def`/`defn` 形式，存为 `{:type :function :name sym :source "..."}`。
     - **Atom**：解引用后若内容可 EDN，则 `{:type :atom :value (deref v)}`。
- **反序列化**：
  - `:function` → 在目标 ctx 里 `sci/eval-string*` 执行 `:source`，恢复定义。
  - `:data` / `:atom` → 用 `sci/intern` 把值写回目标命名空间。

序列化所需参数：**context 本身**、**命名空间**、**最近一条代码字符串**（用于解析函数定义）。不依赖现有 agent/web 代码，可单独测试。

## 模块划分（便于单测）

| 命名空间 | 职责 |
|----------|------|
| `sci-context-serde.edn` | 判定值是否 EDN 可序列化。 |
| `sci-context-serde.classify` | 对绑定值做类型分类（:edn / :atom / :function / :unserializable）。 |
| `sci-context-serde.source` | 从代码字符串解析形式、按符号查找 def/defn 源码。 |
| `sci-context-serde.ctx-access` | 从 SCI ctx 读出指定命名空间的绑定（只读，依赖 SCI 内部 :env）。 |
| `sci-context-serde.serialize` | 单条绑定序列化：serialize-edn / serialize-atom / serialize-function，以及按类型分发的 serialize-one。 |
| `sci-context-serde.deserialize` | 单条快照反序列化：deserialize-edn / deserialize-atom / deserialize-function，以及 deserialize-one。 |
| `sci-context-serde.walk` | 对 context 的「绑定树」做遍历：对每个 (sym, value) 调用给定函数并收集结果。 |
| `sci-context-serde.core` | 对外 API：serialize(ctx opts)、deserialize(snapshot opts)。 |

每个「实际操作」都是独立函数，方便单独测试；顶层只做组合与分发，不写成一整块面条代码。

## 使用方式

```clojure
(require '[sci-context-serde.core :as serde]
         '[sci.core :as sci])

(def ctx (sci/init {:namespaces {'user {}}}))
(sci/eval-string* ctx "(def x 1) (defn f [y] (+ y 1))")

;; 序列化（需提供最近执行的代码，用于提取 defn 源码）
(def snapshot
  (serde/serialize ctx {:namespace 'user
                        :recent-code "(def x 1) (defn f [y] (+ y 1))"}))

;; 快照可写入 EDN / 存库
(pr-str snapshot)

;; 反序列化到新 context
(def ctx2 (sci/init {:namespaces {'user {}}}))
(serde/deserialize snapshot {:ctx ctx2 :namespace 'user})
(sci/eval-string* ctx2 "(f x)") ; => 2
```

## 依赖与测试

- 依赖：`org.clojure/clojure`、`org.clojure/tools.reader`、`org.babashka/sci`。
- 不依赖本仓库的 agent / web-server，可单独运行测试：

```bash
cd backend/components/sci-context-serde
clj -M:test
```

## 限制与约定

- **SCI 内部结构**：`ctx-access` 依赖 SCI 的 `:env`（atom）及 `:namespaces` 结构；若 SCI 升级导致内部格式变化，此处可能需调整。
- **函数**：仅当「该绑定的定义」出现在本次提供的 `recent-code` 中时才能得到源码；若定义在更早的某条消息里而未传入，该函数会变成 `:unserializable` 被跳过。
- **Atom**：只序列化「当前解引用后且可 EDN 的值」；反序列化后是新的 Atom 实例，引用一致性不保留。
