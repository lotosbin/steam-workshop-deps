(ns steam-workshop.neo4j
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [steam-workshop.dotenv :as dotenv])
  (:import [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.net URI]
           [java.time Duration]
           [java.util Base64]))

(def http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 20))
      (.build)))

(defn basic-auth-header [user pass]
  (let [raw (str user ":" pass)
        bytes (.getBytes raw "UTF-8")
        enc (.encodeToString (Base64/getEncoder) bytes)]
    (str "Basic " enc)))

(defn split-auth [s]
  (if (or (nil? s) (str/blank? s))
    [nil nil]
    (let [[u p] (str/split s #"/" 2)]
      [u p])))

(defn http-post-json [^String url ^String body ^String basic-auth]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "Content-Type" "application/json")
                (.header "Authorization" basic-auth)
                (.POST (HttpRequest$BodyPublishers/ofString body))
                (.timeout (Duration/ofSeconds 60))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        raw-body (.body resp)]
    (when (>= status 400)
      (throw (ex-info "HTTP POST failed"
                      {:url url :status status :body raw-body})))
    raw-body))

(defn derive-tx-url [uri-str]
  (when-not (str/blank? uri-str)
    (let [uri (URI/create uri-str)
          host (.getHost uri)]
      (when host
        (str "http://" host ":7474/db/neo4j/tx/commit")))))

(defn tx-url [env-map]
  (or (dotenv/getenv env-map "NEO4J_TX_URL" nil)
      (derive-tx-url (dotenv/getenv env-map "NEO4J_URI" nil))
      "http://localhost:7474/db/neo4j/tx/commit"))

(def node-statement
  "UNWIND $rows AS row MERGE (m:Mod {id: row.id}) SET m += row.props")

(def collection-node-statement
  "UNWIND $rows AS row MERGE (c:Collection {id: row.id}) SET c += row.props")

(def author-node-statement
  "UNWIND $rows AS row MERGE (a:Author {id: row.id}) SET a += row.props")

(def edge-statement
  "UNWIND $rows AS row MERGE (a:Mod {id: row.from}) MERGE (b:Mod {id: row.to}) MERGE (a)-[:REQUIRES]->(b)")

(def collection-edge-statement
  "UNWIND $rows AS row MERGE (c:Collection {id: row.collection_id}) MERGE (m:Mod {id: row.mod_id}) MERGE (c)-[:CONTAINS]->(m)")

(def authored-edge-statement
  "UNWIND $rows AS row MERGE (a:Author {id: row.author_id}) MERGE (m:Mod {id: row.mod_id}) MERGE (a)-[:AUTHORED]->(m)")

(def assembled-edge-statement
  "UNWIND $rows AS row MERGE (a:Author {id: row.author_id}) MERGE (c:Collection {id: row.collection_id}) MERGE (a)-[:ASSEMBLED]->(c)")

(defn node-row
  ([id] (node-row id nil))
  ([id info]
   {:id (str id)
    :props (cond-> {:source "steamcommunity-playwright-cli"
                    :workshop_id (str id)}
             (:title info) (assoc :title (:title info))
             (:author info) (assoc :author (:author info))
             (:author_id info) (assoc :author_id (:author_id info))
             (:author_profile_url info) (assoc :author_profile_url (:author_profile_url info))
             (:canonical_url info) (assoc :canonical_url (:canonical_url info))
             (:preview_url info) (assoc :preview_url (:preview_url info))
             (:posted info) (assoc :posted (:posted info))
             (:updated info) (assoc :updated (:updated info))
             (:file_size info) (assoc :file_size (:file_size info))
             (:description info) (assoc :description (:description info)))}))

(defn collection-row [id info]
  {:id (str id)
   :props (cond-> {:source "steamcommunity-playwright-cli"
                   :workshop_id (str id)
                   :page_type "collection"}
            (:title info) (assoc :title (:title info))
            (:author info) (assoc :author (:author info))
            (:author_id info) (assoc :author_id (:author_id info))
            (:author_profile_url info) (assoc :author_profile_url (:author_profile_url info))
            (:canonical_url info) (assoc :canonical_url (:canonical_url info))
            (:preview_url info) (assoc :preview_url (:preview_url info))
            (:posted info) (assoc :posted (:posted info))
            (:updated info) (assoc :updated (:updated info))
            (:description info) (assoc :description (:description info))
            (:collection_item_ids info) (assoc :collection_item_ids (:collection_item_ids info))
            (:linked_workshop_ids info) (assoc :linked_workshop_ids (:linked_workshop_ids info)))})

(defn edge-row [from to]
  {:from (str from) :to (str to)})

(defn collection-edge-row [collection-id mod-id]
  {:collection_id (str collection-id) :mod_id (str mod-id)})

(defn author-row [info]
  (when-let [author-id (some-> (:author_id info) str not-empty)]
    {:id author-id
     :props (cond-> {:source "steamcommunity-playwright-cli"}
              (:author info) (assoc :name (:author info))
              (:author_profile_url info) (assoc :profile_url (:author_profile_url info)))}))

(defn authored-edge-row [author-id mod-id]
  {:author_id (str author-id) :mod_id (str mod-id)})

(defn assembled-edge-row [author-id collection-id]
  {:author_id (str author-id) :collection_id (str collection-id)})

(defn post-statement! [tx-url basic-auth statement rows]
  (http-post-json tx-url
                  (json/generate-string {:statements [{:statement statement
                                                      :parameters {:rows rows}}]})
                  basic-auth))

(defn query! [tx-url basic-auth statement parameters]
  (let [raw (http-post-json tx-url
                            (json/generate-string
                             {:statements [{:statement statement
                                            :parameters parameters}]})
                            basic-auth)
        body (json/parse-string raw true)
        errors (:errors body)
        result (first (:results body))]
    (when (seq errors)
      (throw (ex-info "Neo4j query failed"
                      {:errors errors
                       :statement statement
                       :parameters parameters})))
    {:columns (:columns result)
     :data (:data result)}))

(defn rows [query-result]
  (mapv (fn [row] (zipmap (:columns query-result) (:row row)))
        (:data query-result)))
