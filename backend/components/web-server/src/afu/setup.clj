(ns afu.setup
  "首次运行 / 新成员加入时执行：安装 sqlite-vec 扩展。
   在 backend 目录运行：clj -M:setup"
  (:require [clojure.java.shell :as shell]))

(defn -main [& _]
  (let [cwd (System/getProperty "user.dir")
        script (str cwd "/scripts/setup-vec.sh")]
    (if-not (.exists (java.io.File. script))
      (do
        (println "错误：未找到 scripts/setup-vec.sh，请在 backend 目录下运行：clj -M:setup")
        (System/exit 1))
      (let [result (shell/sh "bash" script :dir cwd)
            out    (:out result)
            err    (:err result)]
        (when (seq out) (print out))
        (when (seq err) (binding [*out* *err*] (print err)))
        (when (zero? (:exit result))
          (println "完成后可直接运行 clj -M:web-server（deps.edn 已配默认环境变量）"))
        (System/exit (:exit result))))))
