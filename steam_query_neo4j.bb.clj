#!/usr/bin/env bb
;; 查询单个 Steam Workshop 节点及其 REQUIRES 关系

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[steam-workshop.dotenv :as dotenv]
         '[steam-workshop.neo4j :as neo4j])

(def script-dir
  (.getParent (java.io.File. *file*)))

(def dotenv-env
  (dotenv/load-file-map (str script-dir "/.env")))

(defn getenv* [k default]
  (dotenv/getenv dotenv-env k default))

(defn usage! []
  (binding [*out* *err*]
    (println "用法:")
    (println "  bb steam_query_neo4j.bb.clj --id <workshop-id>"))
  (System/exit 1))

(defn parse-args [argv]
  (loop [xs argv
         opts {}]
    (if (empty? xs)
      opts
      (let [[k v & more] xs]
        (when (and k (str/starts-with? k "--") (nil? v))
          (throw (ex-info "参数缺失" {:last k})))
        (cond
          (= k "--id") (recur more (assoc opts :id v))
          (and (not (str/starts-with? (str k) "--")) (nil? (:id opts)))
          (recur (cons v more) (assoc opts :id k))
          :else
          (throw (ex-info "未知参数" {:arg k :value v})))))))

(def node-query
  "MATCH (m:Mod {id: $id})
   RETURN m.id AS id,
          m.workshop_id AS workshop_id,
          m.title AS title,
          m.author AS author,
          m.canonical_url AS canonical_url,
          m.preview_url AS preview_url,
          m.posted AS posted,
          m.updated AS updated,
          m.file_size AS file_size,
          m.source AS source,
          m.description AS description")

(def outgoing-query
  "MATCH (m:Mod {id: $id})-[:REQUIRES]->(dep:Mod)
   RETURN dep.id AS id,
          dep.title AS title
   ORDER BY dep.id")

(def incoming-query
  "MATCH (src:Mod)-[:REQUIRES]->(m:Mod {id: $id})
   RETURN src.id AS id,
          src.title AS title
   ORDER BY src.id")

(defn output-json! [data]
  (println (json/generate-string data {:pretty true})))

(defn -main [& argv]
  (let [opts (parse-args argv)
        id (:id opts)]
    (when-not (and id (re-matches #"\d+" id))
      (usage!))
    (let [neo-auth (or (getenv* "NEO4J_AUTH" "") "neo4j/please_change_me")
          [neo-user neo-pass] (neo4j/split-auth neo-auth)
          _ (when-not neo-user
              (throw (ex-info "NEO4J_AUTH 格式应为 user/pass" {:NEO4J_AUTH neo-auth})))
          tx-url (neo4j/tx-url dotenv-env)
          basic-auth (neo4j/basic-auth-header neo-user neo-pass)
          node-rows (neo4j/rows (neo4j/query! tx-url basic-auth node-query {:id id}))
          outgoing-rows (neo4j/rows (neo4j/query! tx-url basic-auth outgoing-query {:id id}))
          incoming-rows (neo4j/rows (neo4j/query! tx-url basic-auth incoming-query {:id id}))
          node (first node-rows)]
      (output-json! {:id id
                     :exists (boolean node)
                     :node node
                     :requires outgoing-rows
                     :required_by incoming-rows
                     :requires_count (count outgoing-rows)
                     :required_by_count (count incoming-rows)}))))

(apply -main *command-line-args*)
