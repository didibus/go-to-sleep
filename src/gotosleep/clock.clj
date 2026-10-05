(ns gotosleep.clock
  "The trusted clock. Pure: every function takes the raw readings (system wall
  ms, CLOCK_MONOTONIC_RAW ns) as arguments; the FFI reads and the sntp
  subprocess live in the daemon shell.

  Trusted now = anchor wall + elapsed monotonic time. The system wall clock is
  followed only when network time confirms it (two-factor). A persisted wall
  offset keeps a clock that was moved while the daemon watched discounted even
  across a reboot."
  (:require [clojure.string :as str]))

(def confirm-tolerance-ms 2000)
(def drift-ppm 50)

(defn trusted-now
  "Trusted epoch ms at monotonic reading `mono` (ns)."
  [{:keys [wall-ms mono-ns]} mono]
  (+ wall-ms (quot (- mono mono-ns) 1000000)))

(defn- anchor-at
  "A clock anchored so that trusted-now equals `wall` at monotonic `mono`. The
  drift budget starts now, so a fresh anchor whose wall differs from the system
  clock (a forward-clock reboot) is suspect until sntp confirms it."
  [boot-session wall mono]
  {:boot-session boot-session
   :wall-ms wall
   :mono-ns mono
   :wall-offset-ms 0
   :last-trusted-ms wall
   :confirmed-mono-ns mono})

(defn start
  "The clock at daemon start.
  - Same boot session as `persisted`: reuse its anchor unchanged, so killing
    and relaunching the daemon changes nothing.
  - New boot session: anchor at max(sys-wall - wall-offset-ms, last-trusted-ms),
    so a clock moved forward while the daemon watched stays discounted, and a
    backwards step across a reboot is ignored. The anchor is unconfirmed until
    sntp agrees.
  - No persisted state (fresh install): anchor at the system wall.

  Returns {:clock c :event kw}, where the event is :resumed, :fresh,
  :new-boot, or :new-boot-backwards (the system clock is behind the last
  trusted time)."
  [persisted boot-session sys-wall mono]
  (cond
    (nil? persisted)
    {:clock (anchor-at boot-session sys-wall mono) :event :fresh}

    (and (some? boot-session)
         (some? (:boot-session persisted))
         (= boot-session (:boot-session persisted)))
    {:clock persisted :event :resumed}

    :else
    (let [{:keys [wall-offset-ms last-trusted-ms]} persisted
          discounted (- sys-wall (or wall-offset-ms 0))
          anchor-wall (max discounted (or last-trusted-ms discounted))]
      {:clock (anchor-at boot-session anchor-wall mono)
       :event (if (< discounted (or last-trusted-ms discounted))
                :new-boot-backwards
                :new-boot)})))

(defn suspect?
  "Whether the system wall clock disagrees with trusted now beyond the drift
  budget: |sys-wall - trusted| > 2 s + 50 ppm x (mono - confirmed-mono-ns).
  The ppm term is the tolerated drift between the raw monotonic clock and
  NTP-disciplined time since the last confirmation."
  [{:keys [confirmed-mono-ns] :as clock} sys-wall mono]
  (let [elapsed-ms (max 0 (quot (- mono (or confirmed-mono-ns mono)) 1000000))
        threshold (+ confirm-tolerance-ms (quot (* drift-ppm elapsed-ms) 1000000))]
    (> (Math/abs (long (- sys-wall (trusted-now clock mono)))) threshold)))

(defn confirm
  "Apply a network offset `delta-ms` (sntp: network - system wall) at monotonic
  `mono`. When |delta| <= 2 s the system clock and the network agree, so
  re-anchor to sys-wall + delta and record the confirmation. Otherwise the
  clock is unchanged and the system clock is never followed.
  Returns {:clock c :confirmed? bool}."
  [clock sys-wall mono delta-ms]
  (if (<= (Math/abs (long delta-ms)) confirm-tolerance-ms)
    (let [wall (+ sys-wall delta-ms)]
      {:clock (assoc clock :wall-ms wall :mono-ns mono
                     :wall-offset-ms (- sys-wall wall)
                     :last-trusted-ms wall :confirmed-mono-ns mono)
       :confirmed? true})
    {:clock clock :confirmed? false}))

(defn on-persist
  "Clock fields refreshed at each persist: the wall offset (system wall -
  trusted) and the last trusted time, both measured at monotonic `mono`."
  [clock sys-wall mono]
  (let [trusted (trusted-now clock mono)]
    (assoc clock :wall-offset-ms (- sys-wall trusted) :last-trusted-ms trusted)))

;; --- sntp and ntp.conf parsing ---------------------------------------------

(def default-ntp-server "time.apple.com")

(defn ntp-server
  "The first `server` host in /etc/ntp.conf text, else time.apple.com."
  [ntp-conf-text]
  (or (some->> (str/split-lines (or ntp-conf-text ""))
               (map str/trim)
               (some (fn [line]
                       (when-let [[_ host] (re-matches #"(?i)server\s+(\S+).*" line)]
                         host))))
      default-ntp-server))

(defn parse-sntp
  "The offset in ms (network - system wall) from `sntp -t 5` output, or nil.
  The first line of a successful run is `<offset> +/- <error> <host> <ip>`,
  with a signed decimal offset in seconds. A non-zero exit, or anything
  unparseable, gives nil."
  [exit out]
  (when (and (zero? exit) (string? out))
    (let [token (-> out str/split-lines first (str/trim) (str/split #"\s+") first)]
      (when (and token (re-matches #"[-+]?\d+(\.\d+)?" token))
        (try (Math/round (* 1000.0 (Double/parseDouble token)))
             (catch Throwable _ nil))))))
