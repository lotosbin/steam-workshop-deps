(ns steam-workshop.playwright-cli
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def install-browser-hint
  "如果缺少浏览器运行时，执行: npx @playwright/cli install-browser")

(def ^:const rate-limit-cooldown-ms
  "命中 Steam 429 限流时的退避基准（1.5 分钟，逐次翻倍）。"
  90000)

(defn rate-limited?
  "判断异常是否表示「页面被 Steam 挡住」：429 限流，或匿名/数据中心 IP 直接收到登录页。
   判定依据：ex-data 的 :status、:access-gate?，或 :stdout / :page-text 里的
   429 / Too Many Requests / Steam 登录页特征。"
  [ex]
  (let [{:keys [status stdout page-text access-gate?]} (ex-data ex)
        status (when (number? status) (long status))
        page-text (str page-text)]
    (boolean
     (or (= status 429)
         access-gate?
         (some-> stdout (str/includes? "429"))
         (some-> stdout (str/includes? "Too Many Requests"))
         (str/includes? page-text "429")
         (str/includes? page-text "Too Many Requests")
         ;; 登录页特征：title 是 Steam Community 且正文出现 Sign in
         (and (str/includes? page-text "Steam Community")
              (str/includes? page-text "Sign in"))))))

(defn retryable-error?
  "判断异常是否值得重试：playwright 导航超时、Steam 429 限流、HTTP 5xx，或网络层超时。
   ex-data 里带 :status 的是 steam-workshop.importer/http-get-str 抛出的。"
  [ex]
  (let [{:keys [status stdout]} (ex-data ex)
        status (when (number? status) (long status))]
    (boolean
     (or (rate-limited? ex)
         (some-> stdout (str/includes? "Timeout"))
         (and status (>= status 500))
         (re-find #"(?i)timed?\s*out" (str (.getMessage ex)))))))

(defn retry-wait-ms
  "第 attempt 次失败后的等待时长：429 限流用 rate-limit-cooldown-ms 逐次翻倍，
   其余用 sleep-ms*attempt。"
  [attempt sleep-ms ex]
  (if (rate-limited? ex)
    (* (long rate-limit-cooldown-ms) (bit-shift-left 1 (dec (long attempt))))
    (* (long sleep-ms) (long attempt))))

(defn retry!
  "执行 f，最多 attempts 次；仅当抛出可重试异常（见 retryable-error?）时重试。
   Steam 429 限流时按 rate-limit-cooldown-ms 翻倍等待，其余按 sleep-ms*attempt 退避；
   全部失败时抛出最后一次异常。"
  ([attempts f] (retry! attempts 2000 f))
  ([attempts sleep-ms f]
   (loop [n 1]
     (let [result (try
                    {:ok (f)}
                    (catch Exception ex {:ex ex}))]
       (if-let [ex (:ex result)]
         (if (and (< n attempts) (retryable-error? ex))
           (let [rate-limited (rate-limited? ex)
                 wait-ms (retry-wait-ms n sleep-ms ex)]
             (println "warn:" (if rate-limited "命中限流" "可重试失败") n "/" attempts
                      "，等待" (long (/ wait-ms 1000)) "s 后重试:" (.getMessage ex))
             (Thread/sleep (long wait-ms))
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
