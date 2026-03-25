(ns steam-workshop.dotenv
  (:require [clojure.string :as str]))

(defn parse-line [line]
  (let [trimmed (str/trim line)]
    (when (and (not (str/blank? trimmed))
               (not (str/starts-with? trimmed "#")))
      (let [[k v] (str/split trimmed #"=" 2)
            key (some-> k str/trim not-empty)
            value (some-> (or v "")
                          str/trim
                          (str/replace #"^['\"]|['\"]$" ""))]
        (when key [key value])))))

(defn load-file-map [path]
  (let [f (java.io.File. path)]
    (if (.exists f)
      (->> (slurp f)
           str/split-lines
           (keep parse-line)
           (into {}))
      {})))

(defn getenv
  ([env-map k] (getenv env-map k nil))
  ([env-map k default]
   (let [v (or (System/getenv k)
               (get env-map k))]
     (if (and v (not (str/blank? v))) v default))))
