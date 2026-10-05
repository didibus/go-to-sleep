(ns gotosleep.tz-smoke
  "Check offset-at against zdump(8) for every 2026–2027 transition."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [jolt.process :as p]
            [gotosleep.tz :as tz]))

(def months {"Jan" 1 "Feb" 2 "Mar" 3 "Apr" 4 "May" 5 "Jun" 6
             "Jul" 7 "Aug" 8 "Sep" 9 "Oct" 10 "Nov" 11 "Dec" 12})

(defn- zdump-samples
  "[[utc-epoch-s gmtoff] …] from `zdump -v -c 2026,2028 <id>`."
  [id]
  (->> (str/split-lines (:out (p/sh "/usr/sbin/zdump" "-v" "-c" "2026,2028" id)))
       (keep #(re-find #"^\S+\s+\w{3} (\w{3})\s+(\d+) (\d\d):(\d\d):(\d\d) (\d{4}) UT = .* gmtoff=(-?\d+)$" %))
       (map (fn [[_ mon d hh mm ss y off]]
              [(+ (* 86400 (tz/days-from-civil (parse-long y) (months mon) (parse-long d)))
                  (* 3600 (parse-long hh)) (* 60 (parse-long mm)) (parse-long ss))
               (parse-long off)]))
       (filter (fn [[s _]] (<= (* 86400 (tz/days-from-civil 2026 1 1)) s)))))

(deftest offsets-match-zdump
  (doseq [id ["America/Los_Angeles" "Europe/Paris" "Australia/Lord_Howe"]]
    (let [z (tz/load-zone id) samples (zdump-samples id)]
      (is (>= (count samples) 8) id)
      (doseq [[s off] samples]
        (is (= off (tz/offset-at z s)) (str id " at " s))))))
