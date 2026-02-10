(ns resource-store.protocol
  "Resource 存储协议：存、取、删、改，底层实现不可见。"
  (:refer-clojure :exclude [get]))

(defprotocol ResourceStore
  (put! [this content]
    "存 content（string），返回新生成的 resource id。")
  (put-at! [this id content]
    "存 content 到指定 id（新建或覆盖），返回 id。")
  (get* [this id]
    "按 id 取内容，返回 string 或 nil。")
  (delete! [this id]
    "按 id 删除，返回 truthy。"))
