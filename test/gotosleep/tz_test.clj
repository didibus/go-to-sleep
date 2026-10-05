(ns gotosleep.tz-test
  (:require [clojure.test :refer [deftest is testing]]
            [gotosleep.tz :as tz]))

(defn day [y m d] (tz/days-from-civil y m d))
(defn local [y m d hh mm] (+ (* 86400 (day y m d)) (* 3600 hh) (* 60 mm)))
(defn utc [y m d hh mm] (local y m d hh mm))   ; same arithmetic, read as UTC

(defn be-bytes [n width]
  (mapv #(bit-and 0xff (bit-shift-right n (* 8 (- width % 1))))
        (range width)))

(defn tzif-header [timecnt typecnt charcnt]
  (concat [84 90 105 102 (int \2)] (repeat 15 0)
          (mapcat #(be-bytes % 4) [0 0 0 timecnt typecnt charcnt])))

(defn pre-transition-standard-fixture []
  (byte-array
   (concat
    ;; A minimal first (32-bit) block. parse-tzif intentionally reads the
    ;; second, 64-bit block.
    (tzif-header 0 1 1)
    [0 0 0 0 0 0 0]
    ;; The first 64-bit type is DST (+01:00); the second is standard (UTC).
    ;; The transition selects the DST type at epoch second 1000.
    (tzif-header 1 2 8)
    (be-bytes 1000 8)
    [0
     0 0 14 16 1 0
     0 0 0 0 0 4
     68 83 84 0 83 84 68 0])))

(deftest civil
  (is (= 0 (day 1970 1 1)))
  (is (= 10957 (day 2000 1 1)))
  (is (= -1 (day 1969 12 31)))
  (is (= [2026 9 27] (tz/civil-from-days (day 2026 9 27))))
  (testing "round trip over ~220 years"
    (is (every? #(= % (apply tz/days-from-civil (tz/civil-from-days %)))
                (range -30000 50000 7))))
  (is (= 4 (tz/weekday 0)))                  ; 1970-01-01 was a Thursday
  (is (= 7 (tz/weekday (day 2026 9 27))))    ; a Sunday
  (is (= 1 (tz/weekday (day 2026 9 28)))))

(deftest parse-rejects-garbage
  (is (nil? (tz/parse-tzif (byte-array 0))))
  (is (nil? (tz/parse-tzif (.getBytes "not a tzif file at all, definitely not" "UTF-8"))))
  (is (nil? (tz/load-zone "Mars/Olympus")))
  (is (nil? (tz/load-zone "../etc/passwd")))
  (is (nil? (tz/load-zone "")))
  (is (some? (tz/load-zone "UTC"))))

(deftest pre-first-transition-uses-first-standard-type
  (let [z (tz/parse-tzif (pre-transition-standard-fixture))]
    (is (some? z))
    (is (= 0 (:initial z)))
    (is (= 0 (tz/offset-at z 999)))
    (is (= 3600 (tz/offset-at z 1000)))))

(deftest paris
  (let [z (tz/load-zone "Europe/Paris")]
    (is (= 3600 (tz/offset-at z (utc 2026 1 15 12 0))))
    (is (= 7200 (tz/offset-at z (utc 2026 7 15 12 0))))
    (testing "transition instants"
      (is (= 3600 (tz/offset-at z (dec (utc 2026 3 29 1 0)))))
      (is (= 7200 (tz/offset-at z (utc 2026 3 29 1 0))))
      (is (= 7200 (tz/offset-at z (dec (utc 2026 10 25 1 0)))))
      (is (= 3600 (tz/offset-at z (utc 2026 10 25 1 0)))))
    (testing "gap 2026-03-29 02:30 moves forward to 03:30 CEST = 01:30Z"
      (is (= (utc 2026 3 29 1 30) (tz/local->instant z (local 2026 3 29 2 30)))))
    (testing "overlap 2026-10-25 02:30 takes the earlier instant, 00:30Z"
      (is (= (utc 2026 10 25 0 30) (tz/local->instant z (local 2026 10 25 2 30)))))
    (testing "ordinary times round trip"
      (doseq [t [(local 2026 1 1 0 0) (local 2026 6 30 23 59) (local 2026 3 29 3 0)
                 (local 2026 10 25 3 0) (local 2026 10 25 1 59)]]
        (is (= t (tz/instant->local z (tz/local->instant z t))))))))

(deftest los-angeles
  (let [z (tz/load-zone "America/Los_Angeles")]
    (is (= -28800 (tz/offset-at z (utc 2026 1 15 12 0))))
    (is (= -25200 (tz/offset-at z (utc 2026 7 15 12 0))))
    (testing "gap 2026-03-08 02:30 -> 03:30 PDT = 10:30Z"
      (is (= (utc 2026 3 8 10 30) (tz/local->instant z (local 2026 3 8 2 30)))))
    (testing "overlap 2026-11-01 01:30 -> earlier (PDT) = 08:30Z"
      (is (= (utc 2026 11 1 8 30) (tz/local->instant z (local 2026 11 1 1 30)))))))

(deftest lord-howe-half-hour-dst
  (let [z (tz/load-zone "Australia/Lord_Howe")]
    (is (= 37800 (tz/offset-at z (utc 2026 7 1 0 0))))   ; +10:30
    (is (= 39600 (tz/offset-at z (utc 2026 12 1 0 0))))  ; +11:00
    (testing "gap 2026-10-04 02:15 moves forward 30 min"
      (let [s (tz/local->instant z (local 2026 10 4 2 15))]
        (is (= (utc 2026 10 3 15 45) s))
        (is (= (local 2026 10 4 2 45) (tz/instant->local z s)))))
    (testing "overlap 2026-04-05 01:45 takes the earlier instant (+11:00)"
      (is (= (utc 2026 4 4 14 45) (tz/local->instant z (local 2026 4 5 1 45)))))))

(deftest apia-skipped-day
  (let [z (tz/load-zone "Pacific/Apia")
        s (tz/local->instant z (local 2011 12 30 12 0))]
    (testing "2011-12-30 did not exist in Apia; noon moves forward 24 h"
      (is (= (local 2011 12 31 12 0) (tz/instant->local z s))))))

(deftest formatting
  (is (= "07:05" (tz/hhmm (local 2026 1 1 7 5))))
  (is (= "23:59" (tz/hhmm (local 2026 1 1 23 59))))
  (is (= {:days (day 2026 9 27) :year 2026 :month 9 :day 27 :weekday 7 :minute-of-day 1380}
         (tz/local-fields (local 2026 9 27 23 0))))
  (is (= (day 1969 12 31) (tz/local-date-days -1))))
