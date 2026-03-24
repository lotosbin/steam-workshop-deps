#!/usr/bin/env bb
;; Babashka 脚本：从 Steam browse 抓取种子 publishedFileId -> 递归抓取依赖 -> POST 写入 Neo4j 图数据库
;;
;; 说明：
;; - 单个 Workshop 详情通过同目录下的 steam_fetch_workshop_info.bb.clj 抓取。
;; - Neo4j 写入使用 HTTP transaction endpoint（默认 http://localhost:7474/db/neo4j/tx/commit）。
;;
;; 依赖环境变量：
;; - 默认自动读取同目录下的 .env
;; - NEO4J_AUTH（默认 neo4j/please_change_me；也可在 compose 指定）
;; - NEO4J_TX_URL（可选，覆盖 Neo4j tx/commit URL）
;;
;; 示例：
;;   bb steam_import_neo4j.bb.clj --appid 108600 --required-tag "Build 42" --sort totaluniquesubscribers --max-depth 5 --max-nodes 300

(require '[clojure.string :as str]
         '[steam-workshop.dotenv :as dotenv]
         '[steam-workshop.importer :as importer]
         '[steam-workshop.neo4j :as neo4j])

(def script-dir
  (.getParent (java.io.File. *file*)))

(def dotenv-path
  (str script-dir "/.env"))

(def dotenv-env
  (dotenv/load-file-map dotenv-path))

(defn getenv* [k default]
  (dotenv/getenv dotenv-env k default))

(defn parse-args [argv]
  (let [args (atom {:appid 108600
                     :required-tag "Build 42"
                     :sort "lastupdated"
                     :page 1
                     :page-limit 10
                     :max-depth 5
                     :max-nodes 300
                     :sleep-ms 200
                     :batch-edges 80})]
    (loop [xs argv]
      (if (empty? xs)
        @args
        (let [[k v & more] xs]
          (when (and k (str/starts-with? k "--") (nil? v))
            (throw (ex-info "参数缺失" {:last k})))
          (cond
            (= k "--appid") (swap! args assoc :appid v)
            (= k "--required-tag") (swap! args assoc :required-tag v)
            (= k "--sort") (swap! args assoc :sort v)
            (= k "--page") (swap! args assoc :page (Integer/parseInt v))
            (= k "--page-limit") (swap! args assoc :page-limit (Integer/parseInt v))
            (= k "--max-depth") (swap! args assoc :max-depth (Integer/parseInt v))
            (= k "--max-nodes") (swap! args assoc :max-nodes (Integer/parseInt v))
            (= k "--sleep-ms") (swap! args assoc :sleep-ms (Integer/parseInt v))
            (= k "--batch-edges") (swap! args assoc :batch-edges (Integer/parseInt v))
            :else (throw (ex-info "未知参数" {:arg k :value v})))
          (recur more))))))

(defn run! [opts]
  (let [neo-auth (or (getenv* "NEO4J_AUTH" "") "neo4j/please_change_me")
        [neo-user neo-pass] (neo4j/split-auth neo-auth)
        _ (when-not neo-user (throw (ex-info "NEO4J_AUTH 格式应为 user/pass" {:NEO4J_AUTH neo-auth})))
        neo-tx-url (neo4j/tx-url dotenv-env)
        neo-basic (neo4j/basic-auth-header neo-user neo-pass)]
    (importer/import-browse! neo-tx-url neo-basic opts)))

(defn -main [& args]
  (let [opts (parse-args args)]
    (run! opts)))

(apply -main *command-line-args*)
