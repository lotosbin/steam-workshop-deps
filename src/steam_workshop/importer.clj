(ns steam-workshop.importer
  (:require [steam-workshop.neo4j :as neo4j]
            [steam-workshop.playwright-cli :as pw]
            [steam-workshop.workshop :as workshop])
  (:import [java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers]
           [java.net URI URLEncoder]
           [java.time Duration]
           [java.util UUID]))

(def http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 20))
      (.build)))

(defn http-get-str [^String url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.GET)
                (.timeout (Duration/ofSeconds 60))
                (.build))
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        body (.body resp)]
    (when (>= status 400)
      (throw (ex-info "HTTP GET failed"
                      {:url url
                       :status status
                       :body (subs body 0 (min 500 (count body)))})))
    body))

(defn extract-browse-ids [^String html]
  (let [re (re-pattern #"sharedfiles/filedetails/\?id=(\d+)")]
    (->> (re-seq re html)
         (map second)
         (filter #(and % (re-matches #"\d+" %)))
         distinct
         vec)))

(defn browse-url [appid required-tag page]
  (str "https://steamcommunity.com/workshop/browse/?appid=" appid
       "&requiredtags%5B0%5D=" (URLEncoder/encode required-tag "UTF-8")
       "&actualsort=lastupdated"
       "&p=" page
       "&numperpage=30"
       "&browsesort=lastupdated"))

(defn fetch-seed-ids [appid required-tag page page-limit]
  (let [pages (range page (+ page page-limit))]
    (->> pages
         (mapcat (fn [p]
                   (let [url (browse-url appid required-tag p)]
                     (println "browse-url=" url)
                     (extract-browse-ids (http-get-str url)))))
         distinct
         vec)))

(defn open-browser-session! [session]
  (pw/open-session! session "about:blank"))

(defn close-browser-session! [session]
  (pw/close-session! session))

(defn post-node-batch! [neo-tx-url neo-basic nodes]
  (println "POST nodes batch, count=" (count nodes))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/node-statement nodes))

(defn post-edge-batch! [neo-tx-url neo-basic edges]
  (println "POST edges batch, count=" (count edges))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/edge-statement edges))

(defn import-seeds! [tx-url basic-auth seed-ids opts session]
  (let [max-depth (:max-depth opts)
        max-nodes (:max-nodes opts)
        sleep-ms (:sleep-ms opts)
        batch-edges (:batch-edges opts)]
    (when (empty? seed-ids)
      (println "没有可导入的 seed ids")
      (throw (ex-info "empty seed ids" {:opts opts})))
    (println "seed-count=" (count seed-ids))
    (let [visited (atom (into #{} seed-ids))
          queue (atom (mapv #(vector % 0) seed-ids))
          node-buf (atom (mapv neo4j/node-row seed-ids))
          edge-buf (atom [])
          details-cache (atom {})
          total-edges (atom 0)
          discovered-nodes (atom 0)
          skipped-deps (atom 0)]
      (when (seq @node-buf)
        (post-node-batch! tx-url basic-auth (vec @node-buf))
        (reset! node-buf []))
      (println "[2/4] BFS fetch deps + build edges")
      (loop []
        (if-let [[id depth] (first @queue)]
          (do
            (swap! queue subvec 1)
            (when (<= depth max-depth)
              (let [deps (or (get @details-cache id)
                             (let [info (workshop/fetch-info id session)
                                   dep-ids (->> (:dependency_ids info)
                                                (filter #(and % (re-matches #"\d+" %)))
                                                distinct
                                                vec)]
                               (println "fetched item=" id "depth=" depth "deps=" (count dep-ids))
                               (swap! node-buf conj (neo4j/node-row id info))
                               (swap! details-cache assoc id dep-ids)
                               (when (pos? sleep-ms)
                                 (Thread/sleep sleep-ms))
                               dep-ids))
                    known-deps-vec (->> deps
                                        (filter #(contains? @visited %))
                                        vec)
                    new-deps-vec (->> deps
                                      (remove #(contains? @visited %))
                                      vec)
                    remaining-slots (max 0 (- max-nodes @discovered-nodes))
                    admitted-new-deps-vec (vec (take remaining-slots new-deps-vec))
                    allowed-deps-vec (into known-deps-vec admitted-new-deps-vec)]
                (doseq [d allowed-deps-vec]
                  (swap! edge-buf conj (neo4j/edge-row id d)))
                (when (< (count admitted-new-deps-vec) (count new-deps-vec))
                  (swap! skipped-deps + (- (count new-deps-vec) (count admitted-new-deps-vec))))
                (when (seq admitted-new-deps-vec)
                  (swap! node-buf into (mapv neo4j/node-row admitted-new-deps-vec))
                  (swap! visited into admitted-new-deps-vec)
                  (swap! discovered-nodes + (count admitted-new-deps-vec))
                  (swap! queue into (mapv (fn [d] [d (inc depth)]) admitted-new-deps-vec)))))
            (when (seq @node-buf)
              (post-node-batch! tx-url basic-auth (vec @node-buf))
              (reset! node-buf []))
            (when (>= (count @edge-buf) batch-edges)
              (let [edges (vec @edge-buf)]
                (post-edge-batch! tx-url basic-auth edges)
                (swap! total-edges + (count edges))
                (reset! edge-buf [])))
            (recur))
          nil))
      (when (seq @node-buf)
        (post-node-batch! tx-url basic-auth (vec @node-buf))
        (reset! node-buf []))
      (when (seq @edge-buf)
        (let [edges (vec @edge-buf)]
          (println "POST edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/edge-statement edges)
          (swap! total-edges + (count edges))
          (reset! edge-buf [])))
      (println "done")
      (println "visited nodes=" (count @visited))
      (println "discovered dependency nodes=" @discovered-nodes)
      (println "skipped deps due to max-nodes=" @skipped-deps)
      (println "edges posted=" @total-edges))))

(defn import-single! [tx-url basic-auth id opts]
  (let [session (str "sw-single-" (subs (str (UUID/randomUUID)) 0 8))]
    (try
      (open-browser-session! session)
      (println "[1/4] import single root")
      (println "root-id=" id)
      (println "playwright session=" session)
      (import-seeds! tx-url basic-auth [(str id)] opts session)
      (finally
        (close-browser-session! session)))))

(defn import-browse! [tx-url basic-auth opts]
  (let [appid (:appid opts)
        required-tag (:required-tag opts)
        page (:page opts)
        page-limit (:page-limit opts)
        session (str "sw-import-" (subs (str (UUID/randomUUID)) 0 8))]
    (println "[1/4] fetch browse seeds")
    (println "start-page=" page "page-limit=" page-limit)
    (println "playwright session=" session)
    (try
      (open-browser-session! session)
      (let [seed-ids (fetch-seed-ids appid required-tag page page-limit)]
        (when (empty? seed-ids)
          (println "未从 browse 页面提取到任何 publishedFileId；请确认 required-tag、appid 和 page/page-limit 是否正确。"))
        (import-seeds! tx-url basic-auth seed-ids opts session))
      (finally
        (close-browser-session! session)))))
