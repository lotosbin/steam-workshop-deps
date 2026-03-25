#!/usr/bin/env bb
;; 查询单个 Steam Workshop / Collection / Author 节点及其关系

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

(defn require-neo-auth []
  (let [neo-auth (some-> (getenv* "NEO4J_AUTH" nil) str/trim not-empty)]
    (when-not neo-auth
      (throw (ex-info "缺少必需环境变量 NEO4J_AUTH"
                      {:env "NEO4J_AUTH"})))
    neo-auth))

(defn usage! []
  (binding [*out* *err*]
    (println "用法:")
    (println "  bb steam_query_neo4j.bb.clj --id <node-id>"))
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

(def mod-node-query
  "MATCH (m:Mod {id: $id})
   RETURN m.id AS id,
          m.workshop_id AS workshop_id,
          m.title AS title,
          m.obsolete AS obsolete,
          m.author AS author,
          m.author_id AS author_id,
          m.author_profile_url AS author_profile_url,
          m.canonical_url AS canonical_url,
          m.preview_url AS preview_url,
          m.posted AS posted,
          m.updated AS updated,
          m.file_size AS file_size,
          m.source AS source,
          m.description AS description")

(def mod-outgoing-query
  "MATCH (m:Mod {id: $id})-[:REQUIRES]->(dep:Mod)
   RETURN dep.id AS id,
          dep.title AS title,
          dep.obsolete AS obsolete
   ORDER BY dep.id")

(def mod-incoming-query
  "MATCH (src:Mod)-[:REQUIRES]->(m:Mod {id: $id})
   RETURN src.id AS id,
          src.title AS title,
          src.obsolete AS obsolete
   ORDER BY src.id")

(def mod-authors-query
  "MATCH (a:Author)-[:AUTHORED]->(m:Mod {id: $id})
   RETURN a.id AS id,
          a.name AS name,
          a.profile_url AS profile_url
   ORDER BY a.id")

(def mod-collections-query
  "MATCH (c:Collection)-[:CONTAINS]->(m:Mod {id: $id})
   RETURN c.id AS id,
          c.title AS title
   ORDER BY c.id")

(def collection-node-query
  "MATCH (c:Collection {id: $id})
   RETURN c.id AS id,
          c.workshop_id AS workshop_id,
          c.title AS title,
          c.author AS author,
          c.author_id AS author_id,
          c.author_profile_url AS author_profile_url,
          c.canonical_url AS canonical_url,
          c.preview_url AS preview_url,
          c.posted AS posted,
          c.updated AS updated,
          c.source AS source,
          c.description AS description")

(def collection-items-query
  "MATCH (c:Collection {id: $id})-[:CONTAINS]->(m:Mod)
   RETURN m.id AS id,
          m.title AS title,
          m.obsolete AS obsolete
   ORDER BY m.id")

(def collection-authors-query
  "MATCH (a:Author)-[:ASSEMBLED]->(c:Collection {id: $id})
   RETURN a.id AS id,
          a.name AS name,
          a.profile_url AS profile_url
   ORDER BY a.id")

(def author-node-query
  "MATCH (a:Author {id: $id})
   RETURN a.id AS id,
          a.name AS name,
          a.profile_url AS profile_url,
          a.source AS source")

(def author-mods-query
  "MATCH (a:Author {id: $id})-[:AUTHORED]->(m:Mod)
   RETURN m.id AS id,
          m.title AS title,
          m.obsolete AS obsolete
   ORDER BY m.id")

(def author-collections-query
  "MATCH (a:Author {id: $id})-[:ASSEMBLED]->(c:Collection)
   RETURN c.id AS id,
          c.title AS title
   ORDER BY c.id")

(defn output-json! [data]
  (println (json/generate-string data {:pretty true})))

(defn first-row [query-result]
  (first (neo4j/rows query-result)))

(defn query-rows [tx-url basic-auth statement params]
  (neo4j/rows (neo4j/query! tx-url basic-auth statement params)))

(defn detect-kind [tx-url basic-auth id]
  (cond
    (seq (query-rows tx-url basic-auth "MATCH (m:Mod {id: $id}) RETURN m.id AS id LIMIT 1" {:id id})) "mod"
    (seq (query-rows tx-url basic-auth "MATCH (c:Collection {id: $id}) RETURN c.id AS id LIMIT 1" {:id id})) "collection"
    (seq (query-rows tx-url basic-auth "MATCH (a:Author {id: $id}) RETURN a.id AS id LIMIT 1" {:id id})) "author"
    :else nil))

(defn query-mod [tx-url basic-auth id]
  (let [node (first-row (neo4j/query! tx-url basic-auth mod-node-query {:id id}))
        authors (query-rows tx-url basic-auth mod-authors-query {:id id})
        requires (query-rows tx-url basic-auth mod-outgoing-query {:id id})
        required-by (query-rows tx-url basic-auth mod-incoming-query {:id id})
        collections (query-rows tx-url basic-auth mod-collections-query {:id id})]
    {:id id
     :kind "mod"
     :exists (boolean node)
     :node node
     :authors authors
     :collections collections
     :requires requires
     :required_by required-by
     :authors_count (count authors)
     :collections_count (count collections)
     :requires_count (count requires)
     :required_by_count (count required-by)}))

(defn query-collection [tx-url basic-auth id]
  (let [node (first-row (neo4j/query! tx-url basic-auth collection-node-query {:id id}))
        authors (query-rows tx-url basic-auth collection-authors-query {:id id})
        items (query-rows tx-url basic-auth collection-items-query {:id id})]
    {:id id
     :kind "collection"
     :exists (boolean node)
     :node node
     :authors authors
     :contains items
     :authors_count (count authors)
     :contains_count (count items)}))

(defn query-author [tx-url basic-auth id]
  (let [node (first-row (neo4j/query! tx-url basic-auth author-node-query {:id id}))
        mods (query-rows tx-url basic-auth author-mods-query {:id id})
        collections (query-rows tx-url basic-auth author-collections-query {:id id})]
    {:id id
     :kind "author"
     :exists (boolean node)
     :node node
     :authored_mods mods
     :assembled_collections collections
     :authored_mods_count (count mods)
     :assembled_collections_count (count collections)}))

(defn -main [& argv]
  (let [opts (parse-args argv)
        id (:id opts)]
    (when-not (and id (not (str/blank? id)))
      (usage!))
    (let [neo-auth (require-neo-auth)
          [neo-user neo-pass] (neo4j/split-auth neo-auth)
          _ (when-not neo-user
              (throw (ex-info "NEO4J_AUTH 格式应为 user/pass" {:NEO4J_AUTH neo-auth})))
          tx-url (neo4j/tx-url dotenv-env)
          basic-auth (neo4j/basic-auth-header neo-user neo-pass)
          kind (detect-kind tx-url basic-auth id)]
      (output-json!
       (case kind
         "mod" (query-mod tx-url basic-auth id)
         "collection" (query-collection tx-url basic-auth id)
         "author" (query-author tx-url basic-auth id)
         {:id id
          :kind nil
          :exists false
          :node nil})))))

(apply -main *command-line-args*)
