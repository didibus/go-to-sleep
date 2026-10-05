(ns gotosleep.protocol
  "The IPC wire format: framing, safe EDN parsing, request validation, error
  construction, and the English labels and messages. Pure.

  A request is one EDN map on one newline-terminated UTF-8 line, at most
  64 KiB. Everything a client sends is untrusted."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [gotosleep.schedule :as sched]
            [gotosleep.tz :as tz])
  (:import [java.io PushbackReader StringReader]))

(def max-request-bytes 65536)
(def ops #{:status :set-schedule :uninstall})
(def edit-modes #{:open :growth-only})

;; --- UTF-8 -----------------------------------------------------------------

(defn valid-utf8?
  "Strict UTF-8 validation of a byte array: rejects overlongs, surrogate code
  points, and anything above U+10FFFF. Jolt's String decoding silently
  substitutes U+FFFD, so a malformed request has to be caught here."
  [^bytes b]
  (let [n (alength b)
        u (fn [i] (bit-and (aget b i) 0xff))
        cont? (fn [i] (and (< i n) (= 0x80 (bit-and (u i) 0xc0))))]
    (loop [i 0]
      (if (>= i n)
        true
        (let [c (u i)]
          (cond
            (< c 0x80) (recur (inc i))
            (< c 0xc2) false                       ; continuation byte or overlong lead
            (< c 0xe0) (if (cont? (inc i)) (recur (+ i 2)) false)
            (< c 0xf0) (if (and (cont? (inc i)) (cont? (+ i 2))
                                (let [c1 (u (inc i))]
                                  (cond (= c 0xe0) (>= c1 0xa0)   ; no overlong
                                        (= c 0xed) (< c1 0xa0)    ; no surrogates
                                        :else true)))
                         (recur (+ i 3)) false)
            (< c 0xf5) (if (and (cont? (inc i)) (cont? (+ i 2)) (cont? (+ i 3))
                                (let [c1 (u (inc i))]
                                  (cond (= c 0xf0) (>= c1 0x90)   ; no overlong
                                        (= c 0xf4) (< c1 0x90)    ; <= U+10FFFF
                                        :else true)))
                         (recur (+ i 4)) false)
            :else false))))))

;; --- parsing ----------------------------------------------------------------

(defn- reject-tag [_] (throw (ex-info "tagged literal rejected" {})))

(defn parse-request
  "Parses request bytes into a Clojure form, or throws for anything malformed.
  Rejects oversize input, invalid UTF-8, tagged literals (including the
  built-in #inst and #uuid), duplicate keys, and trailing forms."
  [^bytes b]
  (when (> (alength b) max-request-bytes)
    (throw (ex-info "request too large" {})))
  (when-not (valid-utf8? b)
    (throw (ex-info "invalid UTF-8" {})))
  (let [rdr (PushbackReader. (StringReader. (String. b "UTF-8")))
        opts {:eof ::eof :readers {'inst reject-tag 'uuid reject-tag} :default reject-tag}
        form (edn/read opts rdr)]        ; duplicate keys throw here
    (when (= ::eof form)
      (throw (ex-info "empty request" {})))
    (when-not (= ::eof (edn/read opts rdr))
      (throw (ex-info "trailing content" {})))
    form))

(defn- exact-keys? [m ks]
  (and (map? m) (= ks (set (keys m)))))

(defn- freeze-sig? [x]
  (and (exact-keys? x #{:day :start :end})
       ((set sched/days) (:day x))
       (integer? (:start x))
       (integer? (:end x))))

(defn- confirm-freeze? [x]
  (and (vector? x) (every? freeze-sig? x)))

(defn- growth-token? [x]
  (and (exact-keys? x #{:baseline :candidate :zone :authority-frozen
                        :authority-unfrozen-at :confirm-until-at :activates-now})
       (nil? (sched/validate (:baseline x)))
       (nil? (sched/validate (:candidate x)))
       (string? (:zone x))
       (vector? (:authority-frozen x))
       (every? freeze-sig? (:authority-frozen x))
       (= (:authority-frozen x)
          (vec (sort-by (juxt :start :end :day) (:authority-frozen x))))
       (integer? (:authority-unfrozen-at x))
       (integer? (:confirm-until-at x))
       (boolean? (:activates-now x))))

(defn valid-request?
  "Whether a parsed form has the exact shape and field types for its operation."
  [form]
  (and
   (map? form)
   (case (:op form)
     :status (= #{:op} (set (keys form)))
     :uninstall (= #{:op} (set (keys form)))
     :set-schedule
     (let [dry-run? (true? (:dry-run form))
           has-freeze? (contains? form :confirm-freeze)
           has-growth? (contains? form :confirm-growth)]
       (and (contains? form :schedule)
          (empty? (remove #{:op :schedule :dry-run :edit-mode
                            :confirm-freeze :confirm-growth} (keys form)))
          (or (not (contains? form :dry-run))
              (true? (:dry-run form))
              (false? (:dry-run form)))
          (or (not (contains? form :edit-mode))
              (edit-modes (:edit-mode form)))
          (not (and has-freeze? has-growth?))
          (not (and dry-run? (or has-freeze? has-growth?)))
          (or (not has-freeze?) (confirm-freeze? (:confirm-freeze form)))
          (or (not has-growth?) (growth-token? (:confirm-growth form)))))
     false)))

(defn read-request
  "Parses and shape-checks request bytes. Returns [:ok form] or [:bad-request].
  Never throws."
  [^bytes b]
  (try
    (let [form (parse-request b)]
      (if (valid-request? form) [:ok form] [:bad-request]))
    (catch Throwable _ [:bad-request])))

;; --- formatting -------------------------------------------------------------

(defn format-time
  "An epoch-ms instant as \"Tue 07:00\" in `zone`."
  [zone ms]
  (let [local (tz/instant->local zone (quot ms 1000))]
    (str (tz/weekday-abbrev (tz/weekday (tz/local-date-days local))) " " (tz/hhmm local))))

(defn- slot-hhmm [zone ms] (tz/hhmm (tz/instant->local zone (quot ms 1000))))

(defn occ-label
  "An occurrence's human label, e.g. \"Mon 23:00–07:00\"."
  [zone o]
  (str (tz/weekday-abbrev (sched/day->weekday (:day o))) " "
       (slot-hhmm zone (:start o)) "–" (slot-hhmm zone (:end o))))

(defn occ->wire
  "An occurrence as an IPC map. `:today?` lets the agent choose the title form
  without any zone logic of its own."
  [zone now o]
  {:day (:day o) :start (:start o) :end (:end o)
   :frozen-from (sched/frozen-from o)
   :frozen? (sched/frozen? o now) :active? (sched/active? o now)
   :today? (= (tz/local-date-days (tz/instant->local zone (quot (:start o) 1000)))
              (tz/local-date-days (tz/instant->local zone (quot now 1000))))
   :label (occ-label zone o)})

(defn- fmt-hm
  "A millisecond duration as \"8 h 30 min\" (or \"9 h\", \"45 min\")."
  [ms]
  (let [mins (quot ms sched/minute-ms) h (quot mins 60) m (mod mins 60)]
    (cond (zero? h) (str m " min")
          (zero? m) (str h " h")
          :else (str h " h " m " min"))))

;; --- errors -----------------------------------------------------------------

(def ^:private reason-clause
  {:missing-day "every weekday must be present"
   :unknown-key "that field is not recognised"
   :bad-time "the times must be HH:MM"
   :too-short (str "a block must be at least " sched/min-duration-min " minutes")
   :same-start-end "the start and end can't be equal"})

(defn error-message
  "The English sentence for an error map, formatted in `zone`."
  [zone {:keys [code day reason operation unfrozen-at days gap-minutes freezes-now
                confirm-until-at activates-now]}]
  (case code
    :bad-request "Unrecognised request."
    :forbidden "Only the logged-in user can change the schedule."
    :invalid-schedule
    (str (get sched/day-names day "The schedule") ": " (reason-clause reason "is invalid") ".")
    :frozen
    (case operation
      :uninstall
      (str "Go To Sleep cannot be uninstalled until " (format-time zone unfrozen-at) ".")

      :set-schedule
      (str "Schedule is frozen until " (format-time zone unfrozen-at) "."))
    :growth-only
    (str "Schedule is frozen until " (format-time zone unfrozen-at)
         ". Until then, blocks may only be added or made longer.")
    :stale-edit-mode "The schedule state changed. Review and save again."
    :stale-confirmation "The schedule mode changed. Review and save again."
    :window
    (str (sched/day-names (first days)) "'s block would end " (fmt-hm (* gap-minutes sched/minute-ms))
         " before " (sched/day-names (second days)) "'s starts; blocks need at least 9 h between them.")
    :confirm-required
    (if confirm-until-at
      (if activates-now
        (str "Confirming will lock the screen immediately and keep it locked until "
             (format-time zone confirm-until-at) ".")
        (str "These changes cannot be undone until "
             (format-time zone confirm-until-at) "."))
      (str "This takes effect immediately and can't be undone until "
           (format-time zone (apply max (map :end freezes-now))) "."))
    :storage "Couldn't save the schedule; nothing changed."))

(defn error-response
  "An {:ok false :error {...}} response. `err` carries at least :code; internal
  keys are dropped from the wire, confirmation occurrences are converted, and
  the message is added."
  [zone now err]
  (let [wire (cond-> (dissoc err :pair :occurrence :confirm-until-at)
               (= :frozen (:code err)) (dissoc :day)
               (:freezes-now err) (update :freezes-now #(mapv (partial occ->wire zone now) %))
               (:confirm-until-at err)
               (assoc :confirm-until-label (format-time zone (:confirm-until-at err))))]
    {:ok false :error (assoc wire :message (error-message zone err))}))
