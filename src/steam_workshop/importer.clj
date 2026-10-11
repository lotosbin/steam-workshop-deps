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

(defn extract-browse-ids
  "新版 browse 页面的结果在 window.SSR.renderContext 中；
   旧版页面回退到 filedetails 链接正则。"
  [^String html]
  (if-let [ids (workshop/extract-ssr-browse-ids html)]
    ids
    (let [fallback-ids (->> (re-seq #"sharedfiles/filedetails/\?id=(\d+)" html)
                            (map second)
                            (filter #(and % (re-matches #"\d+" %)))
                            distinct
                            vec)]
      (println "warn: 未解析到 window.SSR.renderContext，回退旧版 filedetails 正则，ids=" (count fallback-ids))
      fallback-ids)))

(def extract-workshop-list-ids-script
  (str
   "() => JSON.stringify((() => {"
   "  const uniq = (xs) => Array.from(new Set(xs));"
   "  try {"
   "    const ctx = window.SSR && window.SSR.renderContext;"
   "    const qd = typeof ctx?.queryData === 'string' ? JSON.parse(ctx.queryData) : ctx?.queryData;"
   "    const pick = (qs) => qs.flatMap((q) => (Array.isArray(q?.state?.data?.results) ? q.state.data.results : []));"
   "    const queries = qd?.queries ?? [];"
   "    const browse = queries.filter((q) => q?.queryKey?.[0] === 'workshop_browse');"
   "    const ids = uniq((browse.length ? pick(browse) : pick(queries)).map((r) => r?.publishedfileid).filter(Boolean));"
   "    if (ids.length) return ids;"
   "  } catch (e) {}"
   "  return uniq(Array.from(document.querySelectorAll("
   "    '.workshopBrowseItems .workshopItem a[href*=\"/sharedfiles/filedetails/?id=\"], ' +"
   "    '.workshopBrowseItems .workshopItem a[href*=\"/workshop/filedetails/?id=\"], ' +"
   "    'a.ugc[data-publishedfileid]'))"
   "    .map((a) => a.dataset.publishedfileid || (a.href.match(/[?&]id=(\\d+)/) || [null, null])[1])"
   "    .filter(Boolean));"
   "})())"))

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
   "}))"
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
  (let [eval-result (pw/retry! 2 #(pw/eval! session extract-workshop-list-ids-script))
        raw-result (pw/extract-result (:out eval-result))
        json-text (when raw-result (json/parse-string raw-result))
        ids (when json-text (json/parse-string json-text))]
    (vec (filter #(and % (re-matches #"\d+" %)) ids))))

(defn extract-collection-list-ids! [session]
  (let [eval-result (pw/retry! 2 #(pw/eval! session extract-collection-list-ids-script))
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
                     ;; Steam 偶发 5xx/限流/超时，重试一次再放弃该页
                     (pw/retry! 2 3000 #(extract-browse-ids (http-get-str url))))))
         distinct
         vec)))

(defn fetch-user-workshop-seed-ids! [session base-user-workshop-url user-workshop-section page page-limit]
  (let [pages (range page (+ page page-limit))
        ids (->> pages
                 (mapcat (fn [p]
                           (let [url (paged-user-workshop-url base-user-workshop-url p)]
                             (println "user-workshop-url=" url)
                             (pw/retry! 2 3000 #(pw/goto! session url))
                             (case user-workshop-section
                               "collections" (extract-collection-list-ids! session)
                               (extract-workshop-list-ids! session)))))
                 distinct
                 vec)]
    (when (empty? ids)
      ;; myworkshopfiles 对未登录访客会跳转到登录页，此时页面里没有任何条目链接
      (println (str "warn: 未从 " base-user-workshop-url " 提取到任何条目。"
                    "myworkshopfiles 需要登录态；容器内无 Steam 会话时必然为空，"
                    "请改用 browse 方式（--appid/--required-tag/--sort）或在有登录态的环境运行。")))
    ids))

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

(def cache-ttl-ms
  (* 60 60 1000))

(defn recent-mod-ids [tx-url basic-auth ids]
  (if (seq ids)
    (let [cutoff (- (System/currentTimeMillis) cache-ttl-ms)]
      (neo4j/recent-mod-ids tx-url basic-auth ids cutoff))
    #{}))

(defn recent-collection-ids [tx-url basic-auth ids]
  (if (seq ids)
    (let [cutoff (- (System/currentTimeMillis) cache-ttl-ms)]
      (neo4j/recent-collection-ids tx-url basic-auth ids cutoff))
    #{}))

(defn fetch-parent-collection-ids
  "从 item 详情页的「包含此项目的合集」链接抓取父合集 id（HTTP；失败时用当前 playwright session 兜底）。"
  [session info]
  (let [url (some-> info :parent_collections :parent_collections_browse_url not-empty)]
    (when url
      (or (try
            (let [ids (extract-browse-ids (http-get-str url))]
              (println "parent-collections-url=" url "count=" (count ids))
              (seq ids))
            (catch Exception ex
              (println "warn: 父合集列表抓取失败" (:status (ex-data ex)) (.getMessage ex))
              nil))
          (try
            (pw/goto! session url)
            (let [ids (extract-workshop-list-ids! session)]
              (println "warn: 父合集列表改用浏览器兜底，count=" (count ids))
              (seq ids))
            (catch Exception ex
              (println "warn: 父合集列表浏览器兜底失败" (.getMessage ex))
              nil))))))

(def ^:private rate-limit-note
  "记录最近一次命中 Steam 429 的时间；import-seeds! 用它做全局退避，
   避免每个条目各自等待、把限流越拖越久。"
  (atom 0))

(def ^:dynamic *cooldown-ms*
  "命中 429 后，后续所有页面抓取要统一等待的冷却时间（0 表示关闭）。"
  0)

(defn- note-rate-limit! []
  (reset! rate-limit-note (System/currentTimeMillis)))

(defn- rate-limit-gate!
  "若最近命中过 429，则统一等到冷却结束，让限流有时间恢复。"
  []
  (when (pos? (long *cooldown-ms*))
    (let [until (+ (long @rate-limit-note) (long *cooldown-ms*))
          wait (- until (System/currentTimeMillis))]
      (when (pos? wait)
        (println "warn: 处于 429 限流冷却中，等待" (long (/ wait 1000)) "s")
        (Thread/sleep (long wait))))))

(defn- fetch-info-gated
  "抓详情页，并在命中 429 时记下时间点，供后续请求统一冷却。"
  [id session]
  (rate-limit-gate!)
  (try
    (let [info (workshop/fetch-info id session)]
      (reset! rate-limit-note 0)
      info)
    (catch Exception ex
      (when (pw/rate-limited? ex)
        (note-rate-limit!))
      (throw ex))))

(defn- expand-parent-collections!
  "反向发现：item 详情页里「See all N collections / N 个项目依赖此项目」的链接。
   只展开一层：把这些父合集（以及它们的 CONTAINS 条目）纳入图，
   父合集本身不再继续上溯，也不对其条目做依赖 BFS。"
  [tx-url basic-auth info opts session
   {:keys [details-cache seen-collections visited node-buf collection-buf
           collection-edge-buf discovered-nodes skipped-deps seed-count]}]
  (let [appid (:appid opts)
        ;; 种子节点不计入 BFS 的 discovered-nodes（沿用原语义），这里把种子一起算进总预算
        remaining-budget #(- (:max-nodes opts) (or seed-count 0) @discovered-nodes)
        collection-ids (or (fetch-parent-collection-ids session info) [])]
    (when (seq collection-ids)
      (let [uncached-ids (vec (remove @seen-collections collection-ids))
            recent-collections (recent-collection-ids tx-url basic-auth uncached-ids)
            _ (when (seq recent-collections)
                (println "skip recent imported collections within 1h, count=" (count recent-collections)))
            pending-ids (vec (remove recent-collections uncached-ids))
            imported (atom [])]
        (swap! seen-collections into collection-ids)
        (doseq [coll-id pending-ids]
          (try
          (let [coll-info (or (get @details-cache coll-id)
                              (let [ci (fetch-info-gated coll-id session)]
                                (swap! details-cache assoc coll-id ci)
                                (when (pos? (:sleep-ms opts))
                                  (Thread/sleep (:sleep-ms opts)))
                                ci))
                child-ids (->> (:collection_item_ids coll-info)
                               (filter #(and % (re-matches #"\d+" %)))
                               distinct
                               vec)
                known-children (vec (filter @visited child-ids))
                new-children (vec (remove @visited child-ids))
                known-new-children (recent-mod-ids tx-url basic-auth new-children)
                stale-children (vec (remove known-new-children new-children))
                ;; 父合集节点与其子项一起计入 --max-nodes：名额不够就整体跳过，
                ;; 避免出现「合集导入了但一个条目都没进图」
                admitted? (<= (inc (count stale-children)) (remaining-budget))
                admitted-children (if admitted? stale-children [])]
            (println "parent-collection=" coll-id "children=" (count child-ids)
                     "new=" (count new-children) "cached=" (count known-new-children)
                     "admitted=" (count admitted-children))
            (when-not admitted?
              (swap! skipped-deps + (inc (count stale-children))))
            (when admitted?
              (swap! discovered-nodes + 1 (count admitted-children))
              (swap! node-buf conj (neo4j/node-row coll-id coll-info appid))
              (swap! collection-buf conj (neo4j/collection-row coll-id coll-info appid))
              (when-let [author-row (neo4j/author-row coll-info)]
                (post-author-batch! tx-url basic-auth [author-row])
                (post-assembled-edge-batch! tx-url basic-auth
                                            [(neo4j/assembled-edge-row (:id author-row) coll-id)]))
              (when (seq admitted-children)
                (swap! node-buf into (mapv #(neo4j/node-row % nil appid) admitted-children)))
              ;; 父合集 -> 包含的条目，以及父合集 -> 当前 item
              (doseq [child-id (concat child-ids [(:id info)])]
                (swap! collection-edge-buf conj (neo4j/collection-edge-row coll-id child-id))))
            ;; 缓存命中/已访问的条目只补边不重复建节点
            (swap! visited into known-children)
            (swap! visited into known-new-children)
            (swap! visited into admitted-children)
            (swap! imported conj coll-id))
           ;; 父合集抓取失败（限流/登录页）不能让整轮导入中断，跳过这个合集继续
           (catch Exception ex
             (println "warn: 跳过父合集" coll-id ":" (.getMessage ex)))))
        {:collection-ids @imported}))))

(defn import-seeds! [tx-url basic-auth seed-ids opts session]
  (binding [*cooldown-ms* (long (or (:rate-limit-cooldown-ms opts) 300000))]
   (let [max-depth (:max-depth opts)
        max-nodes (:max-nodes opts)
        sleep-ms (:sleep-ms opts)
        batch-edges (:batch-edges opts)
        appid (:appid opts)]
    (when (empty? seed-ids)
      ;; main.sh 是串行多任务：某个 seed 来源失效（如 myworkshopfiles 需要登录态）
      ;; 不该用异常污染整轮日志和退出码，这里警告并跳过。
      (println "warn: 没有可导入的 seed ids，跳过本次导入（检查 seed 来源 URL / required-tag / appid / 登录态）")
      (throw (ex-info "empty seed ids" {:opts opts :skipped true})))
    (println "seed-count=" (count seed-ids))
    (let [cached-seed-ids (recent-mod-ids tx-url basic-auth seed-ids)
          fresh-seed-ids (vec (remove cached-seed-ids seed-ids))
          _ (when (seq cached-seed-ids)
              (println "skip recent imported seeds within 1h, count=" (count cached-seed-ids)))
          visited (atom (into #{} seed-ids))
          queue (atom (mapv #(vector % 0) fresh-seed-ids))
          node-buf (atom (mapv #(neo4j/node-row % nil appid) fresh-seed-ids))
          author-buf (atom [])
          edge-buf (atom [])
          authored-edge-buf (atom [])
          collection-buf (atom [])
          collection-edge-buf (atom [])
          details-cache (atom {})
          seen-collections (atom #{})
          failed-items (atom #{})
          rate-limited-items (atom #{})
          fetch-attempts (atom {})
          parent-collection-budget-off? (zero? max-nodes)
          total-edges (atom 0)
          discovered-nodes (atom 0)
          skipped-deps (atom 0)
          skipped-recent (atom (count cached-seed-ids))]
      (when (seq @node-buf)
        (post-node-batch! tx-url basic-auth (vec @node-buf))
        (reset! node-buf []))
      (println "[2/4] BFS fetch deps + build edges")
      (loop []
        (if-let [[id depth] (first @queue)]
          (do
            (swap! queue subvec 1)
            (when (<= depth max-depth)
              (let [cached-info (get @details-cache id ::miss)
                    info (if (= ::miss cached-info)
                           (try
                             (fetch-info-gated id session)
                             (catch Exception ex
                               ;; 单个条目失败（超时/限流/登录墙）不该让整轮导入中断
                               (when (pw/rate-limited? ex)
                                 ;; 记录时间点，让后续抓取统一走冷却 gate
                                 (note-rate-limit!)
                                 (swap! rate-limited-items conj id))
                               (swap! failed-items conj id)
                               (println "warn: item=" id "depth=" depth
                                        "抓取失败:" (.getMessage ex))
                               nil))
                           cached-info)]
                (if (nil? info)
                  ;; 首次失败后重排队重试 1 次；429 限流不重排（避免火上浇油），
                  ;; 交给下一轮导入或冷却后由 gate 统一处理
                  (when (and (<= depth max-depth)
                             (not (contains? @rate-limited-items id))
                             (< (long (get @fetch-attempts id 0)) 1))
                    (swap! fetch-attempts update id (fnil inc 0))
                    (swap! queue conj [id depth]))
                  (do
                    (when (= ::miss cached-info)
                      (swap! node-buf conj (neo4j/node-row id info appid))
                      (when-let [author-row (neo4j/author-row info)]
                        (swap! author-buf conj author-row)
                        (swap! authored-edge-buf conj (neo4j/authored-edge-row (:id author-row) id)))
                      (swap! details-cache assoc id info)
                      ;; 反向发现父合集：默认开启（除非 opts 显式 :parent-collections? false），
                      ;; 只对第一层节点展开，且父合集自身不再上溯
                      (when (and (not (false? (:parent-collections? opts)))
                                 (not parent-collection-budget-off?)
                                 (zero? depth))
                        (expand-parent-collections! tx-url basic-auth info
                                                    (assoc opts :max-nodes max-nodes)
                                                    session
                                                    {:details-cache details-cache
                                                     :seen-collections seen-collections
                                                     :visited visited
                                                     :node-buf node-buf
                                                     :collection-buf collection-buf
                                                     :collection-edge-buf collection-edge-buf
                                                     :discovered-nodes discovered-nodes
                                                     :skipped-deps skipped-deps
                                                     :seed-count (count seed-ids)}))
                      (when (pos? sleep-ms)
                        (Thread/sleep (long sleep-ms))))
                    (let [deps (->> (:dependency_ids info)
                                    (filter #(and % (re-matches #"\d+" %)))
                                    distinct
                                    vec)
                          known-deps-vec (->> deps
                                              (filter #(contains? @visited %))
                                              vec)
                          new-deps-vec (->> deps
                                            (remove #(contains? @visited %))
                                            vec)
                          cached-new-deps-vec (vec (recent-mod-ids tx-url basic-auth new-deps-vec))
                          stale-new-deps-vec (vec (remove (set cached-new-deps-vec) new-deps-vec))
                          remaining-slots (max 0 (- max-nodes @discovered-nodes))
                          admitted-new-deps-vec (vec (take remaining-slots stale-new-deps-vec))
                          allowed-deps-vec (into known-deps-vec (into cached-new-deps-vec admitted-new-deps-vec))]
                      (println "fetched item=" id "depth=" depth "deps=" (count deps)
                               "parent-collections=" (or (get-in info [:parent_collections :parent_collections_count]) 0))
                      (doseq [d allowed-deps-vec]
                        (swap! edge-buf conj (neo4j/edge-row id d)))
                      (when (seq cached-new-deps-vec)
                        (swap! skipped-recent + (count cached-new-deps-vec))
                        (swap! visited into cached-new-deps-vec))
                      (when (< (count admitted-new-deps-vec) (count stale-new-deps-vec))
                        (swap! skipped-deps + (- (count stale-new-deps-vec) (count admitted-new-deps-vec))))
                      (when (seq admitted-new-deps-vec)
                        (swap! node-buf into (mapv #(neo4j/node-row % nil appid) admitted-new-deps-vec))
                        (swap! visited into admitted-new-deps-vec)
                        (swap! discovered-nodes + (count admitted-new-deps-vec))
                        (swap! queue into (mapv (fn [d] [d (inc depth)]) admitted-new-deps-vec))))))))
            (when (seq @node-buf)
              (post-node-batch! tx-url basic-auth (vec @node-buf))
              (reset! node-buf []))
            (when (seq @collection-buf)
              (post-collection-batch! tx-url basic-auth (vec @collection-buf))
              (reset! collection-buf []))
            (when (seq @author-buf)
              (post-author-batch! tx-url basic-auth (vec @author-buf))
              (reset! author-buf []))
            (when (>= (count @edge-buf) batch-edges)
              (let [edges (vec @edge-buf)]
                (post-edge-batch! tx-url basic-auth edges)
                (swap! total-edges + (count edges))
                (reset! edge-buf [])))
            (when (>= (count @collection-edge-buf) batch-edges)
              (let [edges (vec @collection-edge-buf)]
                (post-collection-edge-batch! tx-url basic-auth edges)
                (reset! collection-edge-buf [])))
            (when (>= (count @authored-edge-buf) batch-edges)
              (let [edges (vec @authored-edge-buf)]
                (post-authored-edge-batch! tx-url basic-auth edges)
                (reset! authored-edge-buf [])))
            (recur))
          nil))
      (when (seq @node-buf)
        (post-node-batch! tx-url basic-auth (vec @node-buf))
        (reset! node-buf []))
      (when (seq @collection-buf)
        (post-collection-batch! tx-url basic-auth (vec @collection-buf))
        (reset! collection-buf []))
      (when (seq @author-buf)
        (post-author-batch! tx-url basic-auth (vec @author-buf))
        (reset! author-buf []))
      (when (seq @edge-buf)
        (let [edges (vec @edge-buf)]
          (println "POST edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/edge-statement edges)
          (swap! total-edges + (count edges))
          (reset! edge-buf [])))
      (when (seq @collection-edge-buf)
        (let [edges (vec @collection-edge-buf)]
          (println "POST collection edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/collection-edge-statement edges)
          (reset! collection-edge-buf [])))
      (when (seq @authored-edge-buf)
        (let [edges (vec @authored-edge-buf)]
          (println "POST authored edges final batch, count=" (count edges))
          (neo4j/post-statement! tx-url basic-auth neo4j/authored-edge-statement edges)
          (reset! authored-edge-buf [])))
      (println "done")
      (println "visited nodes=" (count @visited))
      (println "discovered dependency nodes=" @discovered-nodes)
      (println "parent collections=" (count @seen-collections))
      (println "failed items (skipped)=" (count @failed-items) (vec (take 20 @failed-items)))
      (println "rate-limited items=" (count @rate-limited-items)
               "(建议等限流恢复后重跑；冷却时间=" (long (/ (long *cooldown-ms*) 1000)) "s)")
      (println "skipped recently imported mods within 1h=" @skipped-recent)
      (println "skipped deps due to max-nodes=" @skipped-deps)
      (println "edges posted=" @total-edges)))))

(defn import-root! [tx-url basic-auth id opts session]
  (println "root-id=" id)
  (let [root-info (workshop/fetch-info id session)
        page-type (:page_type root-info)
        appid (:appid opts)]
    (println "root-page-type=" page-type)
    (if (= page-type "collection")
      (let [collection-item-ids (vec (distinct (:collection_item_ids root-info)))]
        (when (empty? collection-item-ids)
          (println "未从 collection 页面提取到任何条目"))
        (post-collection-batch! tx-url basic-auth [(neo4j/collection-row id root-info appid)])
        (when-let [author-row (neo4j/author-row root-info)]
          (post-author-batch! tx-url basic-auth [author-row])
          (post-assembled-edge-batch! tx-url basic-auth [(neo4j/assembled-edge-row (:id author-row) id)]))
        (when (seq collection-item-ids)
          (post-collection-edge-batch! tx-url basic-auth (mapv #(neo4j/collection-edge-row id %) collection-item-ids))
          (println "[2/4] import collection items requires graph")
          ;; 合集条目作为种子导入时不再反向展开父合集，避免与外层 seed 反复互相上溯
          (import-seeds! tx-url basic-auth collection-item-ids (assoc opts :parent-collections? false) session)))
      (do
        (println "[2/4] import single root")
        (import-seeds! tx-url basic-auth [(str id)] opts session)))))

(defn import-single! [tx-url basic-auth id opts]
  (let [session (str "sw_single_" (subs (str (UUID/randomUUID)) 0 4))]
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
        session (str "sw_browse_" (subs (str (UUID/randomUUID)) 0 4))]
    (println "[1/4] fetch browse seeds")
    (println "sort=" sort "start-page=" page "page-limit=" page-limit)
    (println "parent-collections=on (仅第一层，受 --max-nodes 限制)")
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
