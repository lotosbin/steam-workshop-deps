#!/usr/bin/env bb
;; 导入单个 Steam Workshop 条目及其 Required items 到 Neo4j

(require '[clojure.string :as str]
         '[steam-workshop.dotenv :as dotenv]
         '[steam-workshop.importer :as importer]
         '[steam-workshop.neo4j :as neo4j]
         '[steam-workshop.workshop :as workshop])

(def script-dir
  (.getParent (java.io.File. *file*)))

(def dotenv-env
  (dotenv/load-file-map (str script-dir "/.env")))

(defn getenv* [k default]
  (dotenv/getenv dotenv-env k default))

(defn require-neo-auth []
  (let [neo-auth (some-> (getenv* "NEO4J_AUTH" nil) str/trim not-empty)]
    (when-not neo-auth
      (throw (ex-info "缺少必需环境变量 NEO4J_AUTH"
                      {:env "NEO4J_AUTH"})))
    neo-auth))

(defn usage! []
  (binding [*out* *err*]
    (println "用法:")
    (println "  bb steam_import_single_neo4j.bb.clj --id <workshop-id> [--max-depth 10] [--max-nodes 300]")
    (println "  bb steam_import_single_neo4j.bb.clj --url <steam-sharedfiles-url> [--max-depth 10] [--max-nodes 300]"))
  (System/exit 1))

(defn parse-args [argv]
  (loop [xs argv
         opts {:max-depth 10
               :max-nodes 300
               :sleep-ms 200
               :batch-edges 80}]
    (if (empty? xs)
      opts
      (let [[k v & more] xs]
        (when (and k (str/starts-with? k "--") (nil? v))
          (throw (ex-info "参数缺失" {:last k})))
        (cond
          (= k "--id") (recur more (assoc opts :id v))
          (= k "--url") (recur more (assoc opts :url v))
          (= k "--max-depth") (recur more (assoc opts :max-depth (Integer/parseInt v)))
          (= k "--max-nodes") (recur more (assoc opts :max-nodes (Integer/parseInt v)))
          (= k "--sleep-ms") (recur more (assoc opts :sleep-ms (Integer/parseInt v)))
          (= k "--batch-edges") (recur more (assoc opts :batch-edges (Integer/parseInt v)))
          (and (not (str/starts-with? (str k) "--")) (nil? (:id opts)) (nil? (:url opts))) (recur (cons v more) (assoc opts :id k))
          :else (throw (ex-info "未知参数" {:arg k :value v})))))))

(defn -main [& argv]
  (let [opts (parse-args argv)
        id (workshop/workshop-id opts)]
    (when-not (and id (re-matches #"\d+" id))
      (usage!))
    (let [neo-auth (require-neo-auth)
          [neo-user neo-pass] (neo4j/split-auth neo-auth)
          _ (when-not neo-user (throw (ex-info "NEO4J_AUTH 格式应为 user/pass" {:NEO4J_AUTH neo-auth})))
          neo-basic (neo4j/basic-auth-header neo-user neo-pass)
          tx-url (neo4j/tx-url dotenv-env)]
      (importer/import-single! tx-url neo-basic id opts))))

(apply -main *command-line-args*)
