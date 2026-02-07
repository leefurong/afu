(ns sci-context-serde.store.content-addressable
  "按内容寻址：将 snapshot 拆成 root（名字→hash）+ blob 表（hash→EDN），便于去重与复用。"
  (:require [clojure.edn :as edn]))

(defn- sha256-hex
  "对字符串 s 做 SHA-256，返回 64 位十六进制字符串。"
  [^String s]
  (when s
    (let [md (java.security.MessageDigest/getInstance "SHA-256")
          bytes (.digest md (.getBytes (str s) "UTF-8"))
          hex (BigInteger. 1 bytes)]
      (format "%064x" hex))))

(defn content-hash
  "对单条 binding 的 entry（map）做确定性哈希，用于内容寻址。"
  [entry]
  (sha256-hex (pr-str entry)))

(defn snapshot->root+blobs
  "将完整 snapshot 拆成 root（:namespace + :bindings 为 name→hash）和 blobs（hash→edn-str）。
   返回 {:root {:namespace \"user\" :bindings {\"x\" \"<hash>\" ...}} :blobs {\"<hash>\" \"{:type :data ...}\" ...}}。
   相同内容的 entry 只出现一次，便于存储层去重。"
  [snapshot]
  (when snapshot
    (let [nss (get snapshot :namespace)
          bindings (get snapshot :bindings {})
          {:keys [root blobs]}
          (reduce (fn [acc [name entry]]
                    (let [h (content-hash entry)
                          edn-str (pr-str entry)]
                      (-> acc
                          (update :root assoc-in [:bindings (str name)] h)
                          (assoc-in [:blobs h] edn-str))))
                  {:root {:namespace nss :bindings {}}
                   :blobs {}}
                  bindings)]
      {:root root :blobs blobs})))

(defn root+blobs->snapshot
  "从 root（name→hash）和 blob 取值函数（hash→edn-str）还原完整 snapshot。
   get-blob 形如 (fn [hash] edn-str)，若某 hash 取不到可返回 nil（该 binding 会丢失）。"
  [root get-blob]
  (when (and root (ifn? get-blob))
    (let [nss (get root :namespace)
          name->hash (get root :bindings {})
          bindings (into {}
                        (keep (fn [[name h]]
                                (when-let [edn-str (get-blob h)]
                                  (when-let [entry (edn/read-string edn-str)]
                                    [name entry]))))
                        name->hash)]
      {:namespace nss :bindings bindings})))
