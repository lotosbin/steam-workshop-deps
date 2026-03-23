#!/usr/bin/env bb
;; Babashka 脚本：通过 playwright-cli 获取单个 Steam Workshop 条目信息
;;
;; 用法：
;;   bb steam_fetch_workshop_info.bb.clj --id 3688270372
;;   bb steam_fetch_workshop_info.bb.clj --url "https://steamcommunity.com/sharedfiles/filedetails/?id=3688270372&searchtext="
;;   bb steam_fetch_workshop_info.bb.clj --session sw1 --id 3688270372

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[steam-workshop.workshop :as workshop])

(defn usage! []
  (binding [*out* *err*]
    (println "用法:")
    (println "  bb steam_fetch_workshop_info.bb.clj --id <workshop-id>")
    (println "  bb steam_fetch_workshop_info.bb.clj --url <steam-workshop-url>")
    (println "  bb steam_fetch_workshop_info.bb.clj --session <playwright-session> --id <workshop-id>"))
  (System/exit 1))

(defn parse-args [argv]
  (loop [xs argv
         opts {}]
    (if (empty? xs)
      opts
      (let [[k v & more] xs
            rest-args (cond-> more v (cons v))]
        (cond
          (= k "--id") (recur more (assoc opts :id v))
          (= k "--url") (recur more (assoc opts :url v))
          (= k "--session") (recur more (assoc opts :session v))
          (and (not (str/starts-with? (str k) "--"))
               (nil? (:id opts))
               (nil? (:url opts)))
          (recur rest-args (assoc opts :id k))
          :else (throw (ex-info "未知参数" {:arg k :value v})))))))

(defn output-json! [data]
  (println (json/generate-string data {:pretty true})))

(defn -main [& argv]
  (let [opts (parse-args argv)
        id (workshop/workshop-id opts)
        session (:session opts)]
    (when-not (and id (re-matches #"\d+" id))
      (usage!))
    (try
      (output-json! (workshop/fetch-info id session))
      (catch Exception ex
        (binding [*out* *err*]
          (println (.getMessage ex))
          (when-let [stdout (:stdout (ex-data ex))]
            (when (not (str/blank? stdout))
              (println stdout)))
          (when-let [stderr (:stderr (ex-data ex))]
            (when (not (str/blank? stderr))
              (println stderr)))
          (when-let [hint (:hint (ex-data ex))]
            (println hint)))
        (System/exit 1)))))

(apply -main *command-line-args*)
