(ns steam-workshop.playwright-cli
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def install-browser-hint
  "如果缺少浏览器运行时，执行: npx @playwright/cli install-browser")

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
