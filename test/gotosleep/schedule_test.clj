(ns gotosleep.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [gotosleep.schedule :as s]
            [gotosleep.tz :as tz]
            [gotosleep.rng :as rng]))

(def paris (tz/load-zone "Europe/Paris"))
(def la (tz/load-zone "America/Los_Angeles"))

(defn at
  "Epoch ms of a local wall time in `zone`."
  [zone y m d hh mm]
  (* 1000 (tz/local->instant zone (+ (* 86400 (tz/days-from-civil y m d)) (* 3600 hh) (* 60 mm)))))

(defn slot [a b] {:start a :end b})
(def mon-night (assoc s/empty-schedule :mon (slot "23:00" "07:00")))

;; 2026-09-28 is a Monday.
(def mon-20h (at paris 2026 9 28 20 0))
(def mon-start (at paris 2026 9 28 23 0))
(def tue-end (at paris 2026 9 29 7 0))

(defn edit [s s2 now & [opts]] (s/check-edit s s2 paris now (merge {:dry-run true} opts)))
(defn code [r] (get-in r [:error :code]))

(declare are-reasons)

(deftest validation
  (is (nil? (s/validate s/empty-schedule)))
  (is (nil? (s/validate mon-night)))
  (is (nil? (s/validate (assoc s/empty-schedule :sun (slot "23:00" "23:15")))))
  (are-reasons))

(defn are-reasons []
  (doseq [[sch reason] [[nil :missing-day]
                        [[] :missing-day]
                        [(dissoc s/empty-schedule :wed) :missing-day]
                        [(assoc s/empty-schedule :funday nil) :unknown-key]
                        [(assoc s/empty-schedule :mon (assoc (slot "23:00" "07:00") :x 1)) :unknown-key]
                        [(assoc s/empty-schedule :mon (slot "24:00" "07:00")) :bad-time]
                        [(assoc s/empty-schedule :mon (slot "7:00" "08:00")) :bad-time]
                        [(assoc s/empty-schedule :mon (slot 700 "08:00")) :bad-time]
                        [(assoc s/empty-schedule :mon "23:00") :bad-time]
                        [(assoc s/empty-schedule :mon (slot "23:00" "23:00")) :same-start-end]
                        [(assoc s/empty-schedule :mon (slot "23:00" "23:14")) :too-short]]]
    (is (= reason (:reason (s/validate sch))) (pr-str sch))
    (is (= reason
           (get-in (s/check-edit s/empty-schedule sch paris mon-20h {:dry-run true})
                   [:error :reason]))
        (str "open-state check: " (pr-str sch)))))

(deftest occurrences-and-state
  (let [o (first (s/frozen-occurrences mon-night paris mon-20h))]
    (is (= {:day :mon :start mon-start :end tue-end} (select-keys o [:day :start :end]))))
  (is (= :open (:state (s/state mon-night paris (at paris 2026 9 28 14 59)))))
  (is (= :frozen (:state (s/state mon-night paris (at paris 2026 9 28 15 0)))))
  (is (= :active (:state (s/state mon-night paris mon-start))))
  (is (= :active (:state (s/state mon-night paris (at paris 2026 9 29 3 0)))))
  (is (= :open (:state (s/state mon-night paris tue-end))))
  (is (= tue-end (:unfrozen-at (s/state mon-night paris mon-20h)))))

(deftest fall-back-near-25-hour-occurrence
  ;; LA falls back at 02:00 on Sunday 2026-11-01. This valid 23 h 59 min wall
  ;; block therefore lasts 24 h 59 min in real time.
  (let [sch (assoc s/empty-schedule :sat (slot "03:00" "02:59"))
        date (tz/days-from-civil 2026 10 31)
        o (s/occurrence sch la :sat date)
        now (at la 2026 11 1 2 30)
        st (s/state sch la now)
        deletion (s/check-edit sch s/empty-schedule la now {:dry-run true})]
    (is (= (- (* 25 s/hour-ms) s/minute-ms) (- (:end o) (:start o))))
    (is (> (- now (:start o)) s/day-ms) "the start is more than 24 real hours old")
    (is (s/active? o now))
    (is (= :active (:state st)))
    (is (= [o] (:active st)))
    (is (= [o] (:frozen st)))
    (is (= :growth-only (code deletion)))
    (is (= (:end o) (get-in deletion [:error :unfrozen-at])))
    (is (= :open (:state (s/state sch la (:end o)))))))

(deftest global-growth-only-rule
  (doseq [now [mon-20h mon-start]]
    (testing (str "accepted additions and strict containment in " (:state (s/state mon-night paris now)))
      (doseq [[label candidate]
              [["earlier start" (assoc mon-night :mon (slot "22:00" "07:00"))]
               ["later end" (assoc mon-night :mon (slot "23:00" "08:00"))]
               ["both ends" (assoc mon-night :mon (slot "22:00" "08:00"))]
               ["empty weekday addition" (assoc mon-night :thu (slot "23:00" "07:00"))]
               ["multi-day growth" (assoc mon-night
                                           :mon (slot "22:00" "08:00")
                                           :thu (slot "23:00" "07:00"))]]]
        (let [dry (s/check-edit mon-night candidate paris now {:dry-run true})
              token (:confirm-growth dry)]
          (is (:ok dry) label)
          (is (= :growth-only (:edit-mode dry)) label)
          (is (= mon-night (:baseline token)) label)
          (is (= candidate (:candidate token)) label)
          (is (= "Europe/Paris" (:zone token)) label)
          (is (= tue-end (:authority-unfrozen-at token)) label)
          (is (= :confirm-required
                 (code (s/check-edit mon-night candidate paris now {})))
              label)
          (is (:ok (s/check-edit mon-night candidate paris now
                                 {:confirm-growth token}))
              label))))
    (testing "no-op, shrink, move, disable, and mixed candidates are rejected atomically"
      (let [two (assoc mon-night :thu (slot "23:00" "07:00"))]
        (doseq [[label before candidate]
                [["identical" mon-night mon-night]
                 ["delete" mon-night s/empty-schedule]
                 ["later start" mon-night (assoc mon-night :mon (slot "23:01" "07:00"))]
                 ["earlier end" mon-night (assoc mon-night :mon (slot "23:00" "06:59"))]
                 ["same-duration shift" mon-night (assoc mon-night :mon (slot "22:00" "06:00"))]
                 ["mixed ends" mon-night (assoc mon-night :mon (slot "22:00" "06:59"))]
                 ["one growth plus one shrink" two
                  (assoc two :mon (slot "22:00" "08:00")
                         :thu (slot "23:01" "07:00"))]]]
          (let [r (s/check-edit before candidate paris now {:dry-run true})]
            (is (= :growth-only (code r)) label)
            (is (= :set-schedule (get-in r [:error :operation])) label)
            (is (= tue-end (get-in r [:error :unfrozen-at])) label))))))
  (testing "validation precedes growth classification and window follows it"
    (is (= :invalid-schedule
           (code (s/check-edit mon-night {:mon (slot "not" "valid")}
                               paris mon-20h {:dry-run true}))))
    (is (= :window
           (code (s/check-edit mon-night
                               (assoc mon-night :tue (slot "12:00" "13:00"))
                               paris mon-20h {:dry-run true})))))
  (testing "mode and mode-inapplicable confirmation precedence"
    (let [grow (assoc mon-night :mon (slot "22:00" "08:00"))]
      (is (= :stale-edit-mode
             (code (s/check-edit mon-night grow paris mon-20h
                                 {:dry-run true :edit-mode :open}))))
      (is (= :stale-confirmation
             (code (s/check-edit mon-night grow paris mon-20h
                                 {:confirm-freeze []}))))
      (is (= :stale-confirmation
             (code (s/check-edit s/empty-schedule mon-night paris
                                 (at paris 2026 9 21 12 0)
                                 {:confirm-growth {}}))))))
  (testing "boundaries select open before freeze, growth-only through end - 1, then open"
    (let [shrink (assoc mon-night :mon (slot "23:30" "07:00"))
          grow (assoc mon-night :mon (slot "22:00" "08:00"))
          fr (- mon-start s/freeze-lead-ms)]
      (is (:ok (edit mon-night shrink (dec fr))))
      (doseq [now [fr mon-start (dec tue-end)]]
        (is (= :growth-only (code (edit mon-night shrink now))) (str now))
        (is (:ok (edit mon-night grow now)) (str now)))
      (is (:ok (edit mon-night shrink tue-end))))))

(deftest window-rule
  (let [base (assoc s/empty-schedule :mon (slot "23:00" "07:00"))
        now (at paris 2026 9 21 10 0)]  ; the previous Monday, open state
    (testing "exactly 9 h accepted, less rejected"
      (is (:ok (edit base (assoc base :tue (slot "16:00" "17:00")) now)))
      (let [r (edit base (assoc base :tue (slot "15:59" "17:00")) now)]
        (is (= :window (code r)))
        (is (= [:mon :tue] (get-in r [:error :days])))
        (is (= 539 (get-in r [:error :gap-minutes])))))
    (testing "added blocks are subject to it (A3)"
      (is (= :window (code (edit base (assoc base :tue (slot "12:00" "13:00")) now)))))
    (testing "open-state growth is subject to it"
      (let [two (assoc base :tue (slot "23:00" "07:00"))]
        (is (:ok (edit two (assoc two :mon (slot "23:00" "14:00")) now)))
        (is (= :window (code (edit two (assoc two :mon (slot "23:00" "14:30")) now))))))
    (testing "a pre-existing violation blocks unrelated edits until fully repaired"
      (let [bad (assoc s/empty-schedule :wed (slot "23:00" "07:00") :thu (slot "15:00" "16:00"))]
        (is (= :window (code (edit bad (assoc bad :mon (slot "23:00" "07:00")) now))))
        (is (= :window (code (edit bad (assoc bad :wed (slot "23:00" "06:59")) now))))))))

(deftest window-rule-dst
  (testing "wall-clock 9 h gap over the spring-forward night is 8 h of instants"
    (doseq [[zone y m d] [[la 2026 3 8] [paris 2026 3 29]]]
      ;; Sat 20:00-00:00, then Sun 09:00: 9 h on the wall, 8 h real on DST night.
      (let [base s/empty-schedule
            s2 (assoc base :sat (slot "20:00" "00:00") :sun (slot "09:00" "10:00"))
            twenty-weeks-before (- (at zone y m d 12 0) (* 20 s/week-ms))
            r (s/check-edit base s2 zone twenty-weeks-before {:dry-run true})]
        (is (= :window (code r)) (str y "-" m "-" d))
        (is (= (at zone y m d 9 0) (get-in r [:error :at])))
        (is (= 480 (get-in r [:error :gap-minutes]))))))
  (testing "fall-back nights only lengthen the gap (10 h of instants)"
    (doseq [[zone y m d] [[la 2026 11 1] [paris 2026 10 25]]]
      (let [s2 (assoc s/empty-schedule :sat (slot "20:00" "00:00") :sun (slot "09:00" "10:00"))
            sun9 (at zone y m d 9 0)
            [a b] (s/occurrences s2 zone (- sun9 s/day-ms) sun9)]
        (is (= [:sat :sun] [(:day a) (:day b)]))
        (is (= (* 10 s/hour-ms) (- (:start b) (:end a))))
        ;; and the rule looks a year ahead, so the next spring-forward still rejects it
        (let [r (s/check-edit s/empty-schedule s2 zone (- sun9 (* 4 s/week-ms)) {:dry-run true})]
          (is (= :window (code r)))
          (is (> (get-in r [:error :at]) sun9)))))))

(deftest confirmation-rule
  (let [s2 mon-night
        dry (s/check-edit s/empty-schedule s2 paris mon-20h {:dry-run true})
        fz (:freezes-now dry)]
    (testing "an open-state edit that creates an immediate freeze needs confirmation"
      (is (= 1 (count fz)))
      (is (= mon-start (:start (first fz)))))
    (testing "without a matching echo: :confirm-required with the current list"
      (is (= :confirm-required (code (s/check-edit s/empty-schedule s2 paris mon-20h {}))))
      (is (= :confirm-required
             (code (s/check-edit s/empty-schedule s2 paris mon-20h {:confirm-freeze []}))))
      (is (= :confirm-required
             (code (s/check-edit s/empty-schedule s2 paris mon-20h
                                 {:confirm-freeze [(assoc (first fz) :end 1)]})))))
    (testing "an exact signature echo applies"
      (is (:ok (s/check-edit s/empty-schedule s2 paris mon-20h
                             {:confirm-freeze
                              (s/canonical-freeze-signatures fz)}))))
    (testing "an edit that freezes nothing new needs no confirmation"
      (is (:ok (s/check-edit s/empty-schedule
                             (assoc s/empty-schedule :thu (slot "23:00" "07:00"))
                             paris mon-20h {}))))
    (testing "after apply, an identical dry-run is rejected as a growth-only no-op"
      (is (= :growth-only
             (code (s/check-edit s2 s2 paris mon-20h {:dry-run true})))))
    (testing "an open-state stale echo returns the replacement current list"
      (let [two (assoc s/empty-schedule
                       :mon (slot "23:00" "07:00")
                       :tue (slot "16:00" "17:00"))
            first-dry (s/check-edit s/empty-schedule two paris mon-20h {:dry-run true})
            stale (:freezes-now first-dry)
            later (at paris 2026 9 29 8 0)
            result (s/check-edit s/empty-schedule two paris later {:confirm-freeze stale})
            replacement (get-in result [:error :freezes-now])]
        (is (= [:mon] (mapv :day stale)))
        (is (= :confirm-required (code result)))
        (is (= [:tue] (mapv :day replacement)))))))

(deftest frozen-growth-requires-wall-and-instant-containment
  (testing "Paris spring gap rejects wall-clock growth that moves a resolved instant inward"
    (doseq [[before-slot after-slot]
            [[(slot "03:00" "04:00") (slot "02:59" "04:00")]
             [(slot "01:00" "02:59") (slot "01:00" "03:00")]]]
      (let [date (tz/days-from-civil 2026 3 29)
            before (assoc s/empty-schedule :sun before-slot)
            o (s/occurrence before paris :sun date)
            now (inc (s/frozen-from o))
            after (assoc before :sun after-slot)]
        (is (s/wall-contains-slot? after-slot before-slot))
        (is (= :growth-only
               (code (s/check-edit before after paris now {:dry-run true})))))))
  (testing "fall-back overlap growth contains the authority occurrence in instants"
    (doseq [[zone y m d before-slot after-slot]
            [[la 2026 11 1 (slot "01:30" "02:30") (slot "01:00" "03:00")]
             [(tz/load-zone "Australia/Lord_Howe") 2026 4 5
              (slot "01:45" "02:15") (slot "01:30" "02:30")]]]
      (let [date (tz/days-from-civil y m d)
            day (s/weekday->day (tz/weekday date))
            before (assoc s/empty-schedule day before-slot)
            o (s/occurrence before zone day date)
            now (inc (s/frozen-from o))
            after (assoc before day after-slot)]
        (is (s/wall-contains-slot? after-slot before-slot))
        (is (:ok (s/check-edit before after zone now {:dry-run true}))))))
  (testing "midnight unwrapping distinguishes containment from a shifted interval"
    (is (s/wall-contains-slot? (slot "22:00" "08:00") (slot "23:00" "07:00")))
    (is (not (s/wall-contains-slot? (slot "22:00" "06:59") (slot "23:00" "07:00"))))
    (is (s/wall-contains-slot? (slot "09:00" "13:00") (slot "10:00" "12:00")))
    (is (not (s/wall-contains-slot? (slot "11:00" "13:00") (slot "10:00" "12:00"))))))

(deftest zone-adoption
  (let [frozen-now mon-20h
        open-now (at paris 2026 9 28 12 0)
        spring-window (assoc s/empty-schedule
                             :sat (slot "20:00" "00:00")
                             :sun (slot "09:00" "10:00"))
        before-la-spring (at tz/utc 2026 1 1 12 0)]
    (is (s/zone-adoptable? mon-night paris la open-now)
        "an open, window-valid schedule is adoptable")
    (is (not (s/zone-adoptable? mon-night paris la frozen-now)) "a frozen block would shift")
    (is (s/zone-adoptable? mon-night paris (tz/load-zone "Europe/Berlin") frozen-now)
        "same offsets: adopted at once")
    (is (s/zone-adoptable? s/empty-schedule paris la frozen-now))
    (is (nil? (s/window-violation spring-window tz/utc before-la-spring (set s/days))))
    (is (not (s/zone-adoptable? spring-window tz/utc la before-la-spring))
        "an open zone change is deferred when its DST rules would break the 9 h window")))

;; --- property tests ---------------------------------------------------------

(def zones (mapv tz/load-zone ["UTC" "America/Los_Angeles" "Europe/Paris" "Australia/Lord_Howe"]))

(def anchor-dates
  ;; DST transitions in these zones, plus ordinary dates
  [[2026 3 8] [2026 3 29] [2026 4 5] [2026 10 4] [2026 10 25] [2026 11 1]
   [2026 6 17] [2026 12 2] [2027 1 20]])

(defn hhmm [m] (format "%02d:%02d" (quot m 60) (mod m 60)))

(defn random-slot [r]
  (let [start (* 5 (rng/int! r 288))
        dur (+ 15 (* 5 (rng/int! r 180)))]   ; 15 min .. 15 h
    (slot (hhmm start) (hhmm (mod (+ start dur) 1440)))))

(defn random-schedule [r]
  (into {} (for [d s/days] [d (when (rng/chance! r 0.6) (random-slot r))])))

(defn mutate [r sch]
  (let [d (rng/pick! r s/days)
        sl (get sch d)
        nudge (fn [t k] (hhmm (mod (+ (s/parse-hhmm t) k) 1440)))
        k (rng/pick! r [-60 -1 1 60])]
    (assoc sch d
           (if (nil? sl)
             (random-slot r)
             (case (int (rng/int! r 5))
               0 nil
               1 (update sl :start nudge k)
               2 (update sl :end nudge k)
               3 (-> sl (update :start nudge k) (update :end nudge k))
               4 (-> sl (update :start nudge (- (Math/abs k))) (update :end nudge (Math/abs k))))))))

(defn pick-now [r sch zone]
  (let [[y m d] (rng/pick! r anchor-dates)
        base (* 1000 (tz/local->instant zone (* 86400 (tz/days-from-civil y m d))))
        occs (s/occurrences sch zone (- base s/day-ms) (+ base (* 2 s/day-ms)))]
    (if (and (seq occs) (rng/chance! r 0.9))
      (let [o (rng/pick! r occs)
            fr (s/frozen-from o)]
        (case (int (rng/int! r 6))
          0 (+ fr (rng/pick! r [-60000 -1 0 1 60000]))
          1 (+ (:start o) (rng/int! r (- (:end o) (:start o))))
          2 (+ (:end o) (rng/pick! r [-60000 -1 0 1]))
          (+ fr (rng/int! r (- (:end o) fr)))))
      (+ base (rng/int! r (* 2 s/day-ms))))))

(defn- oracle-wall-end [sl]
  (let [a (s/parse-hhmm (:start sl))
        b (s/parse-hhmm (:end sl))]
    (+ b (if (<= b a) 1440 0))))

(defn- oracle-contains? [outer inner]
  (and (<= (s/parse-hhmm (:start outer)) (s/parse-hhmm (:start inner)))
       (>= (oracle-wall-end outer) (oracle-wall-end inner))))

(def accepted-families [:addition :earlier-start :later-end :both-ends :multi-day])
(def rejected-families [:no-op :delete :later-start :earlier-end :shift :mixed :mixed-multi])

(defn- family-candidate [before day other overnight? family]
  (let [addition (if overnight? (slot "22:00" "06:00") (slot "10:00" "12:00"))
        earlier (if overnight? (slot "21:00" "06:00") (slot "09:00" "12:00"))
        later (if overnight? (slot "22:00" "07:00") (slot "10:00" "13:00"))
        both (if overnight? (slot "21:00" "07:00") (slot "09:00" "13:00"))
        later-start (if overnight? (slot "23:00" "06:00") (slot "11:00" "12:00"))
        earlier-end (if overnight? (slot "22:00" "05:00") (slot "10:00" "11:00"))
        shift (if overnight? (slot "21:00" "05:00") (slot "11:00" "13:00"))
        mixed (if overnight? (slot "21:00" "05:00") (slot "09:00" "11:00"))]
    (case family
      :addition (assoc before other addition)
      :earlier-start (assoc before day earlier)
      :later-end (assoc before day later)
      :both-ends (assoc before day both)
      :multi-day (assoc before day both other addition)
      :no-op before
      :delete (assoc before day nil)
      :later-start (assoc before day later-start)
      :earlier-end (assoc before day earlier-end)
      :shift (assoc before day shift)
      :mixed (assoc before day mixed)
      :mixed-multi (assoc before day both other later-start))))

(deftest property-global-growth-only
  (let [stats (atom {:accepted {:frozen 0 :active 0}
                     :rejected {:frozen 0 :active 0}
                     :open 0 :zones {} :families {}})]
    (doseq [phase [:frozen :active]
            zone zones
            i (range 150)]
      (let [[y m d] (nth anchor-dates (mod i (count anchor-dates)))
            date (tz/days-from-civil y m d)
            day (s/weekday->day (tz/weekday date))
            other (nth s/days (mod (+ 1 (s/day->weekday day)) 7))
            overnight? (odd? i)
            base-slot (if overnight? (slot "22:00" "06:00") (slot "10:00" "12:00"))
            accepted-family (nth accepted-families (mod i (count accepted-families)))
            rejected-family (nth rejected-families (mod i (count rejected-families)))
            rejected-before (if (= :mixed-multi rejected-family)
                              (assoc s/empty-schedule day base-slot other base-slot)
                              (assoc s/empty-schedule day base-slot))
            before (assoc s/empty-schedule day base-slot)
            occurrence (s/occurrence before zone day date)
            rejected-occurrence (s/occurrence rejected-before zone day date)
            now (case phase
                  :frozen (+ (s/frozen-from occurrence) s/hour-ms)
                  :active (+ (:start occurrence) s/minute-ms))
            rejected-now (case phase
                           :frozen (+ (s/frozen-from rejected-occurrence) s/hour-ms)
                           :active (+ (:start rejected-occurrence) s/minute-ms))
            accepted (family-candidate before day other overnight? accepted-family)
            rejected (family-candidate rejected-before day other overnight? rejected-family)
            accepted-result (s/check-edit before accepted zone now {:dry-run true})
            rejected-result (s/check-edit rejected-before rejected zone rejected-now {:dry-run true})
            context {:zone (:id zone) :phase phase :overnight overnight?
                     :accepted-family accepted-family :rejected-family rejected-family}]
        (when (get before day)
          (is (or (= :addition accepted-family)
                  (= :multi-day accepted-family)
                  (oracle-contains? (get accepted day) (get before day)))
              (pr-str context)))
        (is (:ok accepted-result) (pr-str context))
        (is (= :growth-only (code rejected-result)) (pr-str context))
        (swap! stats update-in [:accepted phase] inc)
        (swap! stats update-in [:rejected phase] inc)
        (swap! stats update-in [:zones (:id zone) phase] (fnil inc 0))
        (swap! stats update-in [:families phase accepted-family] (fnil inc 0))
        (swap! stats update-in [:families phase rejected-family] (fnil inc 0))))
    (doseq [zone zones
            i (range 75)]
      (let [[y m d] (nth anchor-dates (mod i (count anchor-dates)))
            date (tz/days-from-civil y m d)
            day (s/weekday->day (tz/weekday date))
            before (assoc s/empty-schedule day (slot "10:00" "12:00"))
            o (s/occurrence before zone day date)
            candidate (assoc before day (slot "11:00" "12:00"))
            result (s/check-edit before candidate zone (dec (s/frozen-from o)) {:dry-run true})]
        (is (:ok result))
        (is (= :open (:edit-mode result)))
        (swap! stats update :open inc)))
    (println "property-global-growth-only" @stats)
    (doseq [phase [:frozen :active]]
      (is (>= (get-in @stats [:accepted phase]) 500))
      (is (>= (get-in @stats [:rejected phase]) 500))
      (doseq [family (concat accepted-families rejected-families)]
        (is (pos? (get-in @stats [:families phase family] 0))
            (str phase " " family))))
    (is (>= (:open @stats) 250))
    (doseq [zone zones phase [:frozen :active]]
      (is (pos? (get-in @stats [:zones (:id zone) phase] 0))
          (str (:id zone) " " phase)))))

(defn unfrozen-hour-between?
  "Brute force: some 60 consecutive minutes strictly between a's end and b's
  start at which no occurrence of `sch` is frozen."
  [sch zone a b]
  (let [occs (s/occurrences sch zone (- (:start a) s/day-ms) (+ (:start b) s/day-ms))]
    (loop [t (:end a) run 0]
      (cond
        (>= run 60) true
        (>= t (:start b)) false
        :else (recur (+ t s/minute-ms)
                     (if (some #(s/frozen? % t) occs) 0 (inc run)))))))

(deftest property-window
  (let [r (rng/make 42)
        accepted (atom 0)]
    (dotimes [_ 800]
      (let [zone (rng/pick! r zones)
            sch (random-schedule r)
            now (pick-now r sch zone)]
        (when (and (= :open (:state (s/state sch zone now)))
                   (nil? (s/window-violation sch zone now (set s/days))))
          (let [sch2 (mutate r sch)
                res (s/check-edit sch sch2 zone now {:dry-run true})]
            (when (:ok res)
              (swap! accepted inc)
              (is (nil? (s/window-violation sch2 zone now (set s/days))))
              ;; spot-check the unfrozen hour by brute force over the next 3 weeks
              (let [occs (s/occurrences sch2 zone now (+ now (* 3 s/week-ms)))]
                (doseq [[a b] (partition 2 1 occs)]
                  (is (unfrozen-hour-between? sch2 zone a b)
                      (pr-str {:zone (:id zone) :sch2 sch2 :a a :b b})))))))))
    (println "property-window accepted" @accepted)
    (is (>= @accepted 50) "coverage floor")))
