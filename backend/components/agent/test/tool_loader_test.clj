(ns tool-loader-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tool-loader :as tool-loader]))

(deftest load-tool-definitions-returns-openai-format
  (testing "加载后的工具为 OpenAI/Moonshot 兼容格式，execute_clojure 的 description 由该工具 spec.clj 自行生成"
    (let [defs (tool-loader/load-tool-definitions)]
      (is (vector? defs))
      (is (seq defs) "至少有一个工具")
      (let [exec (first (filter #(= (get-in % [:function :name]) "execute_clojure") defs))]
        (is (some? exec))
        (is (= "function" (:type exec)))
        (is (string? (get-in exec [:function :description])))
        (is (str/includes? (get-in exec [:function :description]) "环境变量")
            "description 应由 spec.clj 生成并含工具说明")
        (is (get-in exec [:function :parameters :properties :code]))))))
