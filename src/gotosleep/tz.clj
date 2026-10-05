(ns gotosleep.tz
  "Time zones without java.time zone support.

  Parses TZif (v2+, 64-bit section) from the system zoneinfo and answers
  instant -> UTC offset. Civil-date arithmetic is plain integer math, so
  nothing here depends on the process TZ or on jolt-lang/time (whose lookups
  mutate TZ, cost ~1.5 ms, and carry DST offset bugs).

  Conventions: an instant is epoch seconds (UTC). A local time is 'local
  epoch seconds' — the wall clock read as if it were UTC. Days are days since
  1970-01-01."
  (:require [babashka.fs :as fs]
            [gotosleep.observe :as observe]))

;; --- civil dates (Howard Hinnant's algorithms) ----------------------------

(defn days-from-civil
  "Days since 1970-01-01 for a proleptic Gregorian y/m/d."
  [y m d]
  (let [y   (if (<= m 2) (dec y) y)
        era (quot (if (neg? y) (- y 399) y) 400)
        yoe (- y (* era 400))
        mp  (mod (+ m 9) 12)
        doy (+ (quot (+ (* 153 mp) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn civil-from-days
  "[y m d] for days since 1970-01-01."
  [z]
  (let [z   (+ z 719468)
        era (quot (if (neg? z) (- z 146096) z) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y   (+ yoe (* era 400))
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp  (quot (+ (* 5 doy) 2) 153)
        d   (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m   (if (< mp 10) (+ mp 3) (- mp 9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn weekday
  "ISO weekday for days since the epoch: 1 = Monday … 7 = Sunday."
  [days]
  (inc (mod (+ days 3) 7)))

(defn floor-div [a b] (long (Math/floor (/ (double a) (double b)))))

(defn local-date-days
  "The local date (days since epoch) of local epoch seconds."
  [local-s]
  (floor-div local-s 86400))

;; --- TZif ------------------------------------------------------------------

(defn- u8 [^bytes b i] (bit-and (aget b i) 0xff))

(defn- be-int [^bytes b i n]
  (loop [k 0 acc 0]
    (if (= k n) acc (recur (inc k) (bit-or (bit-shift-left acc 8) (u8 b (+ i k)))))))

(defn- be-signed [^bytes b i n]
  (let [v (be-int b i n) bits (* 8 n)]
    (if (and (< n 8) (>= v (bit-shift-left 1 (dec bits))))
      (- v (bit-shift-left 1 bits))
      v)))

(defn- header [^bytes b at]
  (when (and (>= (alength b) (+ at 44))
             (= [84 90 105 102] (mapv #(u8 b (+ at %)) (range 4))))  ; "TZif"
    {:version (u8 b (+ at 4))
     :isutcnt (be-int b (+ at 20) 4) :isstdcnt (be-int b (+ at 24) 4)
     :leapcnt (be-int b (+ at 28) 4) :timecnt (be-int b (+ at 32) 4)
     :typecnt (be-int b (+ at 36) 4) :charcnt (be-int b (+ at 40) 4)}))

(defn- block-size [{:keys [isutcnt isstdcnt leapcnt timecnt typecnt charcnt]} time-size]
  (+ (* timecnt time-size) timecnt (* typecnt 6) charcnt
     (* leapcnt (+ time-size 4)) isstdcnt isutcnt))

(defn parse-tzif
  "Parses TZif bytes into {:transitions [s…] :offsets [off…] :initial off},
  where (:offsets i) applies from (:transitions i) on. Uses the 64-bit block.
  Returns nil for anything malformed."
  [^bytes b]
  (try
    (let [h1 (header b 0)]
      (when (and h1 (>= (:version h1) (int \2)))
        (let [at2 (+ 44 (block-size h1 4))
              {:keys [timecnt typecnt] :as h2} (header b at2)]
          (when (and h2 (pos? typecnt))
            (let [data  (+ at2 44)
                  times (mapv #(be-signed b (+ data (* 8 %)) 8) (range timecnt))
                  idx0  (+ data (* 8 timecnt))
                  idxs  (mapv #(u8 b (+ idx0 %)) (range timecnt))
                  ti0   (+ idx0 timecnt)
                  types (mapv (fn [i]
                                (let [at (+ ti0 (* 6 i))]
                                  {:offset (be-signed b at 4)
                                   :dst? (not (zero? (u8 b (+ at 4))))}))
                              (range typecnt))
                  offs  (mapv :offset types)
                  initial-type (or (first (remove :dst? types)) (first types))]
              (when (and (every? #(< % typecnt) idxs)
                         (= times (sort times)))
                {:transitions times
                 :offsets     (mapv offs idxs)
                 ;; Before the first transition, tzfile(5) uses the first
                 ;; standard-time type, or type zero when none is standard.
                 :initial     (:offset initial-type)}))))))
    (catch Throwable _ nil)))

(def zoneinfo-dir "/var/db/timezone/zoneinfo")

(defn- load-zone* [dir id]
  (when (observe/valid-zone-id? id)
    (let [f (str dir "/" id)]
      (when (fs/regular-file? f)
        (when-let [z (parse-tzif (fs/read-all-bytes f))]
          (assoc z :id id))))))

(def ^:private cache (atom {}))

(defn load-zone
  "The parsed zone for `id` from the system zoneinfo (cached), or nil when the
  id is invalid or its file does not parse."
  ([id] (load-zone zoneinfo-dir id))
  ([dir id]
   (let [k [dir id]]
     (if (contains? @cache k)
       (@cache k)
       (let [z (load-zone* dir id)]
         (when z (swap! cache assoc k z))
         z)))))

(defn valid-zone? [id] (some? (load-zone id)))

(def utc {:id "UTC" :transitions [] :offsets [] :initial 0})

;; --- offsets and conversions ---------------------------------------------

(defn offset-at
  "UTC offset in seconds at instant `s`. Before the first transition the
  initial type applies; after the last, the last offset does."
  [{:keys [transitions offsets initial]} s]
  (let [n (count transitions)]
    (if (or (zero? n) (< s (transitions 0)))
      initial
      ;; greatest i with transitions[i] <= s
      (loop [lo 0 hi (dec n)]
        (if (>= lo hi)
          (offsets lo)
          (let [mid (quot (+ lo hi 1) 2)]
            (if (<= (transitions mid) s) (recur mid hi) (recur lo (dec mid)))))))))

(defn local->instant
  "Instant for local epoch seconds `t`. A time in a DST gap moves forward by
  the gap; an ambiguous time takes the earlier instant."
  [zone t]
  (let [e (offset-at zone (- t 86400))
        l (offset-at zone (+ t 86400))
        valid (filter #(= % (offset-at zone (- t %))) (distinct [e l]))]
    (case (count valid)
      0 (- t e)
      1 (- t (first valid))
      (- t (apply max valid)))))

(defn instant->local
  "Local epoch seconds for instant `s`."
  [zone s]
  (+ s (offset-at zone s)))

;; --- formatting -------------------------------------------------------------

(def weekday-abbrev {1 "Mon" 2 "Tue" 3 "Wed" 4 "Thu" 5 "Fri" 6 "Sat" 7 "Sun"})

(defn- two [n] (if (< n 10) (str "0" n) (str n)))

(defn hhmm
  "HH:MM of local epoch seconds."
  [local-s]
  (let [sod (mod local-s 86400)]
    (str (two (quot sod 3600)) ":" (two (quot (mod sod 3600) 60)))))

(defn local-fields [local-s]
  (let [days (local-date-days local-s)
        [y m d] (civil-from-days days)]
    {:days days :year y :month m :day d :weekday (weekday days)
     :minute-of-day (quot (mod local-s 86400) 60)}))
