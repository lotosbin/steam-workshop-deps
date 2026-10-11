(ns steam-workshop.playwright-cli
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def install-browser-hint
  "如果缺少浏览器运行时，执行: npx @playwright/cli install-browser")

(defn retryable-error?
  "判断异常是否值得重试：playwright 导航超时、HTTP 5xx/429，或网络层超时。
   ex-data 里带 :status 的是 steam-workshop.importer/http-get-str 抛出的。"
  [ex]
  (let [{:keys [status stdout]} (ex-data ex)
        status (when (number? status) (long status))]
    (boolean
     (or (some-> stdout (str/includes? "Timeout"))
         (and status (or (>= status 500) (= status 429)))
         (re-find #"(?i)timed?\s*out" (str (.getMessage ex)))))))

(defn retry!
  "执行 f，最多 attempts 次；仅当抛出可重试异常（见 retryable-error?）时重试。
   退避 sleep-ms*attempt，全部失败时抛出最后一次异常。"
  ([attempts f] (retry! attempts 2000 f))
  ([attempts sleep-ms f]
   (loop [n 1]
     (let [result (try
                    {:ok (f)}
                    (catch Exception ex {:ex ex}))]
       (if-let [ex (:ex result)]
         (if (and (< n attempts) (retryable-error? ex))
           (do
             (println "warn: 可重试失败，" n "/" attempts ":" (.getMessage ex))
             (Thread/sleep (* (long sleep-ms) (long n)))
             (recur (inc n)))
           (throw ex))
         (:ok result))))))

(defn run-cli [& args]
  (apply shell/sh "npx" "@playwright/cli" args))

(defn cli-ok! [result context]
  (when-not (zero? (:exit result))
    (throw (ex-info context
                    {:exit (:exit result)
                     :stdout (str/trim (:out result))
                     :stderr (str/trim (:err result))
                     :hint install-browser-hint})))
  result)

(defn open-session! [session url]
  (cli-ok! (run-cli (str "-s=" session) "open" url)
           "playwright-cli open 失败"))

(defn goto! [session url]
  (cli-ok! (run-cli (str "-s=" session) "goto" url)
           "playwright-cli goto 失败"))

(defn eval! [session js-fn]
  (cli-ok! (run-cli (str "-s=" session) "eval" js-fn)
           "playwright-cli eval 失败"))

(defn close-session! [session]
  (run-cli (str "-s=" session) "close"))

(defn extract-result [s]
  (some->> (re-find #"(?s)### Result\s+(.*?)\s+###" (or s ""))
           second
           str/trim
           not-empty))
