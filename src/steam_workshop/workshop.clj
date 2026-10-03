(ns steam-workshop.workshop
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [steam-workshop.playwright-cli :as pw])
  (:import [java.util UUID]))

(def extract-json-script
  (str
   "() => JSON.stringify({"
   "page_type: document.querySelector('.collectionChildren') ? 'collection' : 'workshop_item',"
   "title: document.querySelector('meta[property=\"og:title\"], meta[name=\"og:title\"]')?.content ?? null,"
   "description: (document.querySelector('meta[property=\"og:description\"], meta[name=\"og:description\"]')?.content || document.querySelector('.workshopItemDescription')?.textContent?.trim() || null),"
   "preview_url: document.querySelector('meta[property=\"og:image\"], meta[name=\"og:image\"]')?.content ?? null,"
   "canonical_url: document.querySelector('meta[property=\"og:url\"], meta[name=\"og:url\"]')?.content ?? location.href,"
   "author: document.querySelector('.friendBlockContent')?.childNodes?.[0]?.textContent?.trim() ?? null,"
   "author_profile_url: document.querySelector('.friendBlockLinkOverlay')?.href ?? null,"
   "posted: (((lefts, rights) => { const i = lefts.findIndex((el) => el.textContent.trim() === 'Posted'); return i >= 0 ? (rights[i]?.textContent?.trim() ?? null) : null; })(Array.from(document.querySelectorAll('.detailsStatLeft')), Array.from(document.querySelectorAll('.detailsStatRight')))),"
   "updated: (((lefts, rights) => { const i = lefts.findIndex((el) => el.textContent.trim() === 'Updated'); return i >= 0 ? (rights[i]?.textContent?.trim() ?? null) : null; })(Array.from(document.querySelectorAll('.detailsStatLeft')), Array.from(document.querySelectorAll('.detailsStatRight')))),"
   "file_size: (((lefts, rights) => { const i = lefts.findIndex((el) => el.textContent.trim() === 'File Size'); return i >= 0 ? (rights[i]?.textContent?.trim() ?? null) : null; })(Array.from(document.querySelectorAll('.detailsStatLeft')), Array.from(document.querySelectorAll('.detailsStatRight')))),"
   "required_item_ids: (() => {"
   "  const panel = Array.from(document.querySelectorAll('.panel')).find((el) => el.querySelector('.rightSectionTopTitle')?.textContent?.trim() === 'Required items');"
   "  if (!panel) return [];"
   "  return Array.from(new Set(Array.from(panel.querySelectorAll('a[href*=\"/sharedfiles/filedetails/?id=\"], a[href*=\"/workshop/filedetails/?id=\"]')).map((a) => (a.href.match(/[?&]id=(\\d+)/) || [null, null])[1]).filter(Boolean)));"
   "})(),"
   "collection_item_ids: (() => {"
   "  const container = document.querySelector('.collectionChildren');"
   "  if (!container) return [];"
   "  return Array.from(new Set(Array.from(container.querySelectorAll('.collectionItemDetails a[href*=\"/sharedfiles/filedetails/?id=\"], .collectionItemDetails a[href*=\"/workshop/filedetails/?id=\"]')).map((a) => (a.href.match(/[?&]id=(\\d+)/) || [null, null])[1]).filter(Boolean)));"
   "})(),"
   "linked_workshop_ids: Array.from(new Set(Array.from(document.querySelectorAll('a[href*=\"/sharedfiles/filedetails/?id=\"], a[href*=\"/workshop/filedetails/?id=\"]')).map((a) => (a.href.match(/[?&]id=(\\d+)/) || [null, null])[1]).filter(Boolean)))"
   "})"))

;; 列表页（/workshop/browse/、myworkshopfiles）已改为 React SSR 渲染，
;; 旧版 .workshopBrowseItems/.workshopItem 选择器与裸 href 正则都不再可靠；
;; 结构化数据在内联脚本 window.SSR.renderContext=JSON.parse("...") 的 query cache 中。
(def ssr-render-context-marker
  "window.SSR.renderContext=JSON.parse(")

(defn- js-string-end
  "返回 JS 字符串字面量结束引号的索引（从 from 开始扫描，跳过转义字符）。"
  [^String s from]
  (loop [i from esc? false]
    (when (< i (count s))
      (let [c (.charAt s i)]
        (cond
          esc? (recur (inc i) false)
          (= c \\) (recur (inc i) true)
          (= c \") i
          :else (recur (inc i) false))))))

(defn extract-ssr-render-context
  "从页面 HTML 的 window.SSR.renderContext=JSON.parse(\"<json>\") 解析 SSR 上下文；
   旧版页面或解析失败时返回 nil。"
  [html]
  (when-let [marker-start (str/index-of html ssr-render-context-marker)]
    (let [literal-start (str/index-of html "\"" (+ marker-start (count ssr-render-context-marker)))]
      (when literal-start
        (when-let [literal-end (js-string-end html (inc literal-start))]
          (try
            (json/parse-string
             (json/parse-string (subs html literal-start (inc literal-end)))
             true)
            (catch Exception _ nil)))))))

(defn ssr-query-results
  "取出 SSR query cache 中 workshop_browse 查询的 results；
   找不到时退回所有带 results 的查询。"
  [render-context]
  (let [query-data (:queryData render-context)
        query-data (if (string? query-data) (json/parse-string query-data true) query-data)
        queries (:queries query-data)
        results-of (fn [qs] (->> qs
                                 (mapcat #(get-in % [:state :data :results]))
                                 (filter map?)))
        browse-results (results-of (filter #(= "workshop_browse" (first (:queryKey %))) queries))]
    (or (seq browse-results) (results-of queries))))

(defn extract-ssr-browse-ids
  "从新版列表页 HTML 提取 publishedFileId 列表（保留顺序、去重）；解析不到时返回 nil。"
  [html]
  (some->> html
           extract-ssr-render-context
           ssr-query-results
           (keep :publishedfileid)
           (map str)
           (filter #(re-matches #"\d+" %))
           distinct
           not-empty
           vec))

(defn extract-id-from-url [s]
  (some->> (re-find #"(?i)[?&]id=(\d+)" (str s))
           second))

(defn workshop-id [opts]
  (or (:id opts)
      (extract-id-from-url (:url opts))))

(defn extract-author-id-from-url [s]
  (when-not (str/blank? s)
    (or (some->> (re-find #"steamcommunity\.com/profiles/([^/?#]+)" s)
                 second)
        (some->> (re-find #"steamcommunity\.com/id/([^/?#]+)" s)
                 second))))

(defn fetch-info
  ([id] (fetch-info id nil))
  ([id session]
   (let [url (str "https://steamcommunity.com/sharedfiles/filedetails/?id=" id)
         owned-session? (str/blank? session)
         session (or (not-empty session) (str "sw-" (subs (str (UUID/randomUUID)) 0 8)))]
     (try
       (if owned-session?
         (pw/open-session! session url)
         (pw/goto! session url))
       (let [eval-result (pw/eval! session extract-json-script)
             raw-result (pw/extract-result (:out eval-result))
             json-text (when raw-result (json/parse-string raw-result))
             data (when json-text (json/parse-string json-text true))
             page-type (or (:page_type data) "workshop_item")
             author-profile-url (some-> data :author_profile_url not-empty)
             author-id (or (extract-author-id-from-url author-profile-url)
                           (some-> data :author not-empty))
             required-ids (vec (filter #(and % (re-matches #"\d+" %))
                                       (:required_item_ids data)))
             collection-item-ids (vec (filter #(and % (re-matches #"\d+" %))
                                              (:collection_item_ids data)))
             linked-ids (vec (filter #(and % (re-matches #"\d+" %))
                                     (:linked_workshop_ids data)))
             dependency-ids (if (= page-type "collection")
                              collection-item-ids
                              (vec (remove #(= % id) required-ids)))]
         (when-not data
           (throw (ex-info "未能从 playwright-cli 输出中提取 JSON"
                           {:stdout (:out eval-result)})))
         (assoc data
                :id id
                :page_type page-type
                :author_profile_url author-profile-url
                :author_id author-id
                :required_item_ids required-ids
                :collection_item_ids collection-item-ids
                :dependency_ids dependency-ids
                :linked_workshop_ids linked-ids
                :source "steamcommunity-playwright-cli"))
       (finally
         (when owned-session?
           (pw/close-session! session)))))))
