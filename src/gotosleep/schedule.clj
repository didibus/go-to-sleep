(ns gotosleep.schedule
  "The schedule model, editing rules, and time-zone adoption. Pure. Instants
  are epoch milliseconds; zones come from gotosleep.tz."
  (:require [gotosleep.tz :as tz]))

(def days [:mon :tue :wed :thu :fri :sat :sun])
(def day->weekday (zipmap days (range 1 8)))
(def weekday->day (zipmap (range 1 8) days))
(def day-names {:mon "Monday" :tue "Tuesday" :wed "Wednesday" :thu "Thursday"
                :fri "Friday" :sat "Saturday" :sun "Sunday"})

(def minute-ms 60000)
(def hour-ms (* 60 minute-ms))
(def day-ms (* 24 hour-ms))
(def occurrence-lookback-ms (* 2 day-ms))
(def week-ms (* 7 day-ms))
(def freeze-lead-ms (* 8 hour-ms))
(def min-gap-ms (* 9 hour-ms))
(def warn-lead-ms (* 5 minute-ms))
(def min-duration-min 15)

(def empty-schedule (zipmap days (repeat nil)))

;; --- well-formedness -------------------------------------------------------

(defn parse-hhmm
  "Minutes since midnight for a valid \"HH:MM\", else nil."
  [s]
  (when (string? s)
    (when-let [[_ h m] (re-matches #"([01]\d|2[0-3]):([0-5]\d)" s)]
      (+ (* 60 (parse-long h)) (parse-long m)))))

(defn duration-min
  "Wall-clock minutes of a slot; the end is the next :end after :start."
  [{:keys [start end]}]
  (mod (- (parse-hhmm end) (parse-hhmm start)) 1440))

(defn- slot-problem [slot]
  (cond
    (nil? slot) nil
    (not (map? slot)) :bad-time
    (seq (remove #{:start :end} (keys slot))) :unknown-key
    (not (and (parse-hhmm (:start slot)) (parse-hhmm (:end slot)))) :bad-time
    (zero? (duration-min slot)) :same-start-end
    (< (duration-min slot) min-duration-min) :too-short))

(defn validate
  "nil when `s` is a well-formed schedule, else
  {:code :invalid-schedule :day d :reason r}."
  [s]
  (cond
    (not (map? s))
    {:code :invalid-schedule :day nil :reason :missing-day}

    (seq (remove (set days) (keys s)))
    {:code :invalid-schedule :day (first (remove (set days) (keys s))) :reason :unknown-key}

    :else
    (or (some (fn [d] (when-not (contains? s d)
                        {:code :invalid-schedule :day d :reason :missing-day}))
              days)
        (some (fn [d] (when-let [r (slot-problem (get s d))]
                        {:code :invalid-schedule :day d :reason r}))
              days))))

;; --- occurrences ----------------------------------------------------------

(defn occurrence
  "The occurrence of `day`'s slot on local date `date` (days since epoch), or
  nil when the slot is empty. Assumes a well-formed schedule and that `date`
  falls on `day`."
  [schedule zone day date]
  (when-let [{:keys [start end] :as slot} (get schedule day)]
    (let [sm (parse-hhmm start)
          em (parse-hhmm end)
          end-date (if (> em sm) date (inc date))
          s (* 1000 (tz/local->instant zone (+ (* 86400 date) (* 60 sm))))
          e (* 1000 (tz/local->instant zone (+ (* 86400 end-date) (* 60 em))))]
      (when slot
        {:day day :date date :start s
         ;; A DST gap can resolve the end before the start; retain the minimum duration.
         :end (max e (+ s (* min-duration-min minute-ms)))}))))

(defn occurrences
  "Occurrences whose start lies in [from-ms, to-ms], sorted by start."
  [schedule zone from-ms to-ms]
  (let [d0 (dec (tz/local-date-days (tz/instant->local zone (quot from-ms 1000))))
        d1 (inc (tz/local-date-days (tz/instant->local zone (quot to-ms 1000))))]
    (->> (range d0 (inc d1))
         (keep (fn [date]
                 (occurrence schedule zone (weekday->day (tz/weekday date)) date)))
         (filter #(<= from-ms (:start %) to-ms))
         (sort-by :start)
         vec)))

(defn active? [o now] (and (<= (:start o) now) (< now (:end o))))
(defn frozen? [o now] (and (<= (- (:start o) freeze-lead-ms) now) (< now (:end o))))
(defn frozen-from [o] (- (:start o) freeze-lead-ms))

(defn horizon
  "The occurrences the rules consider at `now`: starting in
  [now - 48 h, now + 53 weeks]. A slot is less than 24 wall-clock hours, but
  can approach 48 real hours when a time-zone offset moves backward."
  [schedule zone now]
  (occurrences schedule zone (- now occurrence-lookback-ms) (+ now (* 53 week-ms))))

(defn frozen-occurrences
  "Occurrences frozen at `now`. The 48-hour lookback retains near-24-hour wall
  blocks whose real duration is longer across a backward offset transition."
  [schedule zone now]
  (->> (occurrences schedule zone (- now occurrence-lookback-ms) (+ now freeze-lead-ms))
       (filter #(frozen? % now))
       vec))

(defn state
  "{:state :active|:frozen|:open :unfrozen-at ms-or-nil :frozen [occ…] :active [occ…]}."
  [schedule zone now]
  (let [fr (frozen-occurrences schedule zone now)
        ac (filterv #(active? % now) fr)]
    {:state (cond (seq ac) :active (seq fr) :frozen :else :open)
     :unfrozen-at (when (seq fr) (apply max (map :end fr)))
     :frozen fr
     :active ac}))

;; --- rules -----------------------------------------------------------------

(defn contains-occ? [outer inner]
  (and outer (<= (:start outer) (:start inner)) (>= (:end outer) (:end inner))))

(defn edit-mode
  "The authoritative schedule edit mode at `now`."
  [schedule zone now]
  (if (seq (frozen-occurrences schedule zone now)) :growth-only :open))

(defn changed-days [s s2] (set (filter #(not= (get s %) (get s2 %)) days)))

(defn window-violation
  "The first consecutive pair in the complete occurrence horizon of `s2` that
  are less than min-gap apart, as a :window error, else nil. The fourth
  argument is retained for source compatibility and deliberately ignored:
  existing violations are never grandfathered."
  [s2 zone now _changed]
  (let [occs (horizon s2 zone now)]
    (some (fn [[a b]]
            (when (< (- (:start b) (:end a)) min-gap-ms)
              {:code :window :days [(:day a) (:day b)]
               :gap-minutes (quot (- (:start b) (:end a)) minute-ms)
               :at (:start b) :pair [a b]}))
          (partition 2 1 occs))))

(defn- occ-key [o] [(:day o) (:start o) (:end o)])

(defn freeze-signature [o]
  (select-keys o [:day :start :end]))

(defn canonical-freeze-signatures [occs]
  (->> occs
       (map freeze-signature)
       (sort-by (juxt :start :end :day))
       vec))

(defn- wall-end
  [{:keys [start end]}]
  (let [sm (parse-hhmm start)
        em (parse-hhmm end)]
    (+ em (if (<= em sm) 1440 0))))

(defn wall-contains-slot?
  "Whether candidate slot `outer` contains baseline slot `inner` on the same
  weekday's unwrapped 24-hour wall-clock timeline."
  [outer inner]
  (and (map? outer)
       (map? inner)
       (<= (parse-hhmm (:start outer)) (parse-hhmm (:start inner)))
       (>= (wall-end outer) (wall-end inner))))

(defn- growth-only-valid?
  [s s2 zone now]
  (let [changed (changed-days s s2)
        wall-ok? (every?
                  (fn [day]
                    (let [before (get s day)
                          after (get s2 day)]
                      (cond
                        (nil? before) (map? after)
                        (map? after) (and (not= before after)
                                          (wall-contains-slot? after before))
                        :else false)))
                  changed)
        instants-ok? (every?
                      (fn [old-occ]
                        (contains-occ?
                         (occurrence s2 zone (:day old-occ) (:date old-occ))
                         old-occ))
                      (frozen-occurrences s zone now))]
    (and (seq changed) wall-ok? instants-ok?)))

(defn freezes-now
  "Occurrences of `s2` frozen at `now` that are not identical to a frozen
  occurrence of `s`."
  [s s2 zone now]
  (let [before (set (map occ-key (frozen-occurrences s zone now)))]
    (filterv #(not (before (occ-key %))) (frozen-occurrences s2 zone now))))

(defn confirmation-matches?
  "Whether the echoed :confirm-freeze list exactly names `fz`."
  [confirm fz]
  (= confirm (canonical-freeze-signatures fz)))

(defn growth-token
  "Canonical confirmation token for a valid growth-only candidate."
  [s s2 zone now]
  (let [authority (state s zone now)
        candidate (state s2 zone now)
        candidate-frozen (:frozen candidate)]
    {:baseline s
     :candidate s2
     :zone (:id zone)
     :authority-frozen (canonical-freeze-signatures (:frozen authority))
     :authority-unfrozen-at (:unfrozen-at authority)
     :confirm-until-at (apply max (map :end candidate-frozen))
     :activates-now (boolean
                     (and (empty? (:active authority))
                          (seq (:active candidate))))}))

(defn- growth-error [s zone now]
  {:code :growth-only
   :operation :set-schedule
   :unfrozen-at (:unfrozen-at (state s zone now))})

(defn check-edit
  "Applies the schedule-mutation rules to replacing `s` with `s2` at `now`.
  Returns mode-specific success data or {:ok false :error {...}}. Request
  shape is validated by gotosleep.protocol before this function."
  [s s2 zone now {:keys [dry-run confirm-freeze confirm-growth] :as opts}]
  (let [supplied-mode (:edit-mode opts)
        mode (edit-mode s zone now)]
    (cond
      (and (contains? opts :edit-mode) (not= supplied-mode mode))
      {:ok false :error {:code :stale-edit-mode :expected mode :actual supplied-mode}}

      (or (and (= :open mode) (contains? opts :confirm-growth))
          (and (= :growth-only mode) (contains? opts :confirm-freeze)))
      {:ok false :error {:code :stale-confirmation}}

      :else
      (if-let [e (validate s2)]
        {:ok false :error e}
        (if (and (= :growth-only mode)
                 (not (growth-only-valid? s s2 zone now)))
          {:ok false :error (growth-error s zone now)}
          (if-let [e (window-violation s2 zone now nil)]
            {:ok false :error e}
            (if (= :open mode)
              (let [fz (freezes-now s s2 zone now)]
                (if (and (seq fz) (not dry-run)
                         (not (confirmation-matches? confirm-freeze fz)))
                  {:ok false :error {:code :confirm-required :freezes-now fz}}
                  {:ok true :edit-mode mode :freezes-now fz}))
              (let [token (growth-token s s2 zone now)]
                (if (or dry-run (= confirm-growth token))
                  {:ok true :edit-mode mode :confirm-growth token}
                  {:ok false
                   :error {:code :confirm-required
                           :confirm-growth token
                           :confirm-until-at (:confirm-until-at token)
                           :activates-now (:activates-now token)}})))))))))

;; --- zone adoption ------------------------------------------------------------

(defn zone-adoptable?
  "Whether switching the effective zone from `z` to `z2` keeps every occurrence
  frozen at `now` (under `z`) contained in its counterpart (same weekday and
  local date) under `z2`, and leaves the complete schedule window-valid under
  `z2`."
  [schedule z z2 now]
  (and
   (every? (fn [o] (contains-occ? (occurrence schedule z2 (:day o) (:date o)) o))
           (frozen-occurrences schedule z now))
   (nil? (window-violation schedule z2 now (set days)))))
