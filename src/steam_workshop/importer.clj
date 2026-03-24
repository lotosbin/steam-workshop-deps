(ns steam-workshop.importer
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [steam-workshop.neo4j :as neo4j]
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

(def extract-workshop-list-ids-script
  (str
   "() => JSON.stringify("
   "Array.from(new Set("
   "Array.from(document.querySelectorAll('.workshopBrowseItems .workshopItem a[href*=\"/sharedfiles/filedetails/?id=\"], .workshopBrowseItems .workshopItem a[href*=\"/workshop/filedetails/?id=\"]'))"
   ".map((a) => (a.href.match(/[?&]id=(\\d+)/) || [null, null])[1])"
   ".filter(Boolean)"
   ")))"))

(def extract-collection-list-ids-script
  (str
   "() => JSON.stringify("
   "Array.from(new Set("
   "Array.from(document.querySelectorAll('a[href*=\"/sharedfiles/filedetails/?id=\"], a[href*=\"/workshop/filedetails/?id=\"]'))"
   ".map((a) => ({"
   "  href: a.href,"
   "  text: (a.textContent || '').trim(),"
   "  parentClass: a.parentElement?.className ?? '',"
   "  className: a.className ?? ''"
   "})))"
   ".filter((row) => {"
   "  const id = (row.href.match(/[?&]id=(\\d+)/) || [null, null])[1];"
   "  if (!id) return false;"
   "  const hay = `${row.text} ${row.parentClass} ${row.className}`.toLowerCase();"
   "  return hay.includes('collection');"
   "})"
   ".map((row) => (row.href.match(/[?&]id=(\\d+)/) || [null, null])[1])"
   ".filter(Boolean)"
   ")))"))

(defn browse-url [appid required-tag sort page]
  (str "https://steamcommunity.com/workshop/browse/?appid=" appid
       "&requiredtags%5B0%5D=" (URLEncoder/encode required-tag "UTF-8")
       "&actualsort=" (URLEncoder/encode (or sort "lastupdated") "UTF-8")
       "&p=" page
       "&numperpage=30"
       "&browsesort=" (URLEncoder/encode (or sort "lastupdated") "UTF-8")))

(defn paged-user-workshop-url [base-url page]
  (let [uri (URI/create base-url)
        raw-query (.getRawQuery uri)
        query-parts (if (str/blank? raw-query)
                      []
                      (vec (str/split raw-query #"&")))
        kept-parts (->> query-parts
                        (remove #(or (str/starts-with? % "p=")
                                     (str/starts-with? % "numperpage=")))
                        vec)
        new-query (str/join "&" (concat kept-parts
                                        [(str "p=" page)
                                         "numperpage=30"]))]
    (str (.getScheme uri) "://" (.getAuthority uri) (.getPath uri)
         (when-not (str/blank? new-query)
           (str "?" new-query)))))

(defn extract-workshop-list-ids! [session]
  (let [eval-result (pw/eval! session extract-workshop-list-ids-script)
        raw-result (pw/extract-result (:out eval-result))
        json-text (when raw-result (json/parse-string raw-result))
        ids (when json-text (json/parse-string json-text))]
    (vec (filter #(and % (re-matches #"\d+" %)) ids))))

(defn extract-collection-list-ids! [session]
  (let [eval-result (pw/eval! session extract-collection-list-ids-script)
        raw-result (pw/extract-result (:out eval-result))
        json-text (when raw-result (json/parse-string raw-result))
        ids (when json-text (json/parse-string json-text))]
    (vec (filter #(and % (re-matches #"\d+" %)) ids))))

(defn fetch-seed-ids [appid required-tag sort page page-limit]
  (let [pages (range page (+ page page-limit))]
    (->> pages
         (mapcat (fn [p]
                   (let [url (browse-url appid required-tag sort p)]
                     (println "browse-url=" url)
                     (extract-browse-ids (http-get-str url)))))
         distinct
         vec)))

(defn fetch-user-workshop-seed-ids! [session base-user-workshop-url user-workshop-section page page-limit]
  (let [pages (range page (+ page page-limit))]
    (->> pages
         (mapcat (fn [p]
                   (let [url (paged-user-workshop-url base-user-workshop-url p)]
                     (println "user-workshop-url=" url)
                     (pw/goto! session url)
                     (case user-workshop-section
                       "collections" (extract-collection-list-ids! session)
                       (extract-workshop-list-ids! session)))))
         distinct
         vec)))

(defn open-browser-session! [session]
  (pw/open-session! session "about:blank"))

(defn close-browser-session! [session]
  (pw/close-session! session))

(defn post-node-batch! [neo-tx-url neo-basic nodes]
  (println "POST nodes batch, count=" (count nodes))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/node-statement nodes))

(defn post-collection-batch! [neo-tx-url neo-basic collections]
  (println "POST collections batch, count=" (count collections))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/collection-node-statement collections))

(defn post-author-batch! [neo-tx-url neo-basic authors]
  (println "POST authors batch, count=" (count authors))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/author-node-statement authors))

(defn post-edge-batch! [neo-tx-url neo-basic edges]
  (println "POST edges batch, count=" (count edges))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/edge-statement edges))

(defn post-collection-edge-batch! [neo-tx-url neo-basic edges]
  (println "POST collection edges batch, count=" (count edges))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/collection-edge-statement edges))

(defn post-authored-edge-batch! [neo-tx-url neo-basic edges]
  (println "POST authored edges batch, count=" (count edges))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/authored-edge-statement edges))

(defn post-assembled-edge-batch! [neo-tx-url neo-basic edges]
  (println "POST assembled edges batch, count=" (count edges))
  (neo4j/post-statement! neo-tx-url neo-basic neo4j/assembled-edge-statement edges))

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
          author-buf (atom [])
          edge-buf (atom [])
          authored-edge-buf (atom [])
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
                               (when-let [author-row (neo4j/author-row info)]
                                 (swap! author-buf conj author-row)
                                 (swap! authored-edge-buf conj (neo4j/authored-edge-row (:id author-row) id)))
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
            (when (seq @author-buf)
              (post-author-batch! tx-url basic-auth (vec @author-buf))
              (reset! author-buf []))
            (when (>= (count @edge-buf) batch-edges)
              (let [edges (vec @edge-buf)]
                (post-edge-batch! tx-url basic-auth edges)
                (swap! total-edges + (count edges))
                (reset! edge-buf [])))
            (when (>= (count @authored-edge-buf) batch-edges)
              (let [edges (vec @authored-edge-buf)]
                (post-authored-edge-batch! tx-url basic-auth edges)
                (reset! authored-edge-buf [])))
            (recur))
          nil))
      (when (seq @node-buf)
        (post-node-batch! tx-url basic-auth (vec @node-buf))
        (reset! node-buf []))
      (when (seq @author-buf)
        (post-author-batch! tx-url basic-auth (vec @author-buf))
        (reset! author-buf []))
      (when (seq @edge-buf)
        (let [edges (vec @edge-buf)]
          (println "POST edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/edge-statement edges)
          (swap! total-edges + (count edges))
          (reset! edge-buf [])))
      (when (seq @authored-edge-buf)
        (let [edges (vec @authored-edge-buf)]
          (println "POST authored edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/authored-edge-statement edges)
          (reset! authored-edge-buf [])))
      (println "done")
      (println "visited nodes=" (count @visited))
      (println "discovered dependency nodes=" @discovered-nodes)
      (println "skipped deps due to max-nodes=" @skipped-deps)
      (println "edges posted=" @total-edges))))

(defn import-root! [tx-url basic-auth id opts session]
  (println "root-id=" id)
  (let [root-info (workshop/fetch-info id session)
        page-type (:page_type root-info)]
    (println "root-page-type=" page-type)
    (if (= page-type "collection")
      (let [collection-item-ids (vec (distinct (:collection_item_ids root-info)))]
        (when (empty? collection-item-ids)
          (println "未从 collection 页面提取到任何条目"))
        (post-collection-batch! tx-url basic-auth [(neo4j/collection-row id root-info)])
        (when-let [author-row (neo4j/author-row root-info)]
          (post-author-batch! tx-url basic-auth [author-row])
          (post-assembled-edge-batch! tx-url basic-auth [(neo4j/assembled-edge-row (:id author-row) id)]))
        (when (seq collection-item-ids)
          (post-collection-edge-batch! tx-url basic-auth (mapv #(neo4j/collection-edge-row id %) collection-item-ids))
          (println "[2/4] import collection items requires graph")
          (import-seeds! tx-url basic-auth collection-item-ids opts session)))
      (do
        (println "[2/4] import single root")
        (import-seeds! tx-url basic-auth [(str id)] opts session)))))

(defn import-single! [tx-url basic-auth id opts]
  (let [session (str "sw-single-" (subs (str (UUID/randomUUID)) 0 8))]
    (try
      (open-browser-session! session)
      (println "[1/4] inspect root page")
      (println "playwright session=" session)
      (import-root! tx-url basic-auth id opts session)
      (finally
        (close-browser-session! session)))))

(defn import-browse! [tx-url basic-auth opts]
  (let [appid (:appid opts)
        required-tag (:required-tag opts)
        sort (:sort opts)
        user-workshop-url (:user-workshop-url opts)
        user-workshop-section (:user-workshop-section opts)
        page (:page opts)
        page-limit (:page-limit opts)
        session (str "sw-import-" (subs (str (UUID/randomUUID)) 0 8))]
    (println "[1/4] fetch browse seeds")
    (println "sort=" sort "start-page=" page "page-limit=" page-limit)
    (when user-workshop-url
      (println "user-workshop-url=" user-workshop-url "section=" user-workshop-section))
    (println "playwright session=" session)
    (try
      (open-browser-session! session)
      (let [seed-ids (if user-workshop-url
                       (fetch-user-workshop-seed-ids! session user-workshop-url user-workshop-section page page-limit)
                       (fetch-seed-ids appid required-tag sort page page-limit))]
        (when (empty? seed-ids)
          (println "未提取到任何 publishedFileId；请确认 seed 来源 URL、required-tag、appid 和 page/page-limit 是否正确。"))
        (if (= user-workshop-section "collections")
          (doseq [id seed-ids]
            (println "[2/4] import user collection root")
            (import-root! tx-url basic-auth id opts session))
          (import-seeds! tx-url basic-auth seed-ids opts session)))
      (finally
        (close-browser-session! session)))))
