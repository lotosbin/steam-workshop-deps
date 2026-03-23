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

(def edge-statement
  "UNWIND $rows AS row MERGE (a:Mod {id: row.from}) MERGE (b:Mod {id: row.to}) MERGE (a)-[:REQUIRES]->(b)")

(defn node-row
  ([id] (node-row id nil))
  ([id info]
   {:id (str id)
    :props (cond-> {:source "steamcommunity-playwright-cli"
                    :workshop_id (str id)}
             (:title info) (assoc :title (:title info))
             (:author info) (assoc :author (:author info))
             (:canonical_url info) (assoc :canonical_url (:canonical_url info))
             (:preview_url info) (assoc :preview_url (:preview_url info))
             (:posted info) (assoc :posted (:posted info))
             (:updated info) (assoc :updated (:updated info))
             (:file_size info) (assoc :file_size (:file_size info))
             (:description info) (assoc :description (:description info)))}))

(defn edge-row [from to]
  {:from (str from) :to (str to)})

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
