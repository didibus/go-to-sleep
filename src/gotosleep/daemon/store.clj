(ns gotosleep.daemon.store
  "Persisted daemon state. Atomic writes use F_FULLFSYNC and rename(2),
  serialized by a lock so the tick loop and the SIGTERM hook never interleave.
  Reads happen only at startup; a corrupt file is quarantined."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [gotosleep.schedule :as sched]
            [gotosleep.tz :as tz]
            [jolt.ffi :as ffi])
  (:import [java.io PushbackReader StringReader]))

(ffi/load-library)
(ffi/defcfn c-open "open" [:string :int :& :int] :int {:capture-native-error true})
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t {:capture-native-error true})
(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int {:capture-native-error true})
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-rename "rename" [:string :string] :int {:capture-native-error true})

(def ^:private O_WRONLY 1)
(def ^:private O_CREAT 0x200)
(def ^:private O_TRUNC 0x400)
(def ^:private O_NOFOLLOW 0x100)
(def ^:private O_CLOEXEC 0x1000000)
(def ^:private F_FULLFSYNC 51)

(def ^:private io-lock (Object.))

(def format-version 1)

(defn persist-fields
  "The persisted subset of the daemon state."
  [state]
  {:format format-version
   :schedule (:schedule state)
   :zone (:zone state)
   :restore-disablesleep (boolean (:restore-disablesleep state))
   :clock (:clock state)})

(defn write-atomic!
  "Write `content` to `path` durably: a private temp file, F_FULLFSYNC (plain
  fsync doesn't flush the APFS drive cache), then rename. Throws on any failure
  so the caller can report :storage. Serialized by a process-wide lock."
  [path content mode]
  (locking io-lock
    (let [tmp (str path ".tmp")
          bs (.getBytes ^String content "UTF-8")
          [fd e] (c-open tmp (bit-or O_WRONLY O_CREAT O_TRUNC O_NOFOLLOW O_CLOEXEC) mode)]
      (when (neg? fd) (throw (ex-info "open failed" {:errno e :path tmp})))
      (try
        (ffi/with-alloc [buf (max 1 (alength bs))]
          (ffi/write-array buf bs)
          (let [[n e] (c-write fd buf (alength bs))]
            (when (not= n (alength bs)) (throw (ex-info "short write" {:n n :errno e})))))
        (let [[r e] (c-fcntl fd F_FULLFSYNC 0)]
          (when (neg? r) (throw (ex-info "fullfsync failed" {:errno e}))))
        (finally (c-close fd)))
      (let [[r e] (c-rename tmp path)]
        (when (neg? r) (throw (ex-info "rename failed" {:errno e}))))
      :ok)))

(defn save!
  "Persist the state to `path` (mode 0600). Throws on failure."
  [path state]
  (write-atomic! path (pr-str (persist-fields state)) 0600))

(def ^:private persisted-keys
  #{:format :schedule :zone :restore-disablesleep :clock})

(def ^:private required-clock-keys
  #{:boot-session :wall-ms :mono-ns :wall-offset-ms :last-trusted-ms :confirmed-mono-ns})

(def ^:private optional-clock-keys
  #{:active-occurrence-key :active-since-mono :active-since-trusted-ms})

(defn- valid-active-tracking? [clock]
  (let [present (set (filter #(contains? clock %) optional-clock-keys))
        key (:active-occurrence-key clock)]
    (or
     (empty? present)
     ;; Compatibility with the pre-occurrence-scoped format-1 state. Core init
     ;; ignores this legacy anchor and reconstructs timing from the occurrence.
     (and (= #{:active-since-mono} present)
          (integer? (:active-since-mono clock)))
     (and (= optional-clock-keys present)
          (vector? key)
          (= 2 (count key))
          ((set sched/days) (first key))
          (integer? (second key))
          (integer? (:active-since-mono clock))
          (integer? (:active-since-trusted-ms clock))))))

(defn- valid-clock? [clock]
  (and (map? clock)
       (every? #(contains? clock %) required-clock-keys)
       (empty? (remove (into required-clock-keys optional-clock-keys) (keys clock)))
       (or (nil? (:boot-session clock)) (string? (:boot-session clock)))
       (every? integer? (map clock
                             [:wall-ms :mono-ns :wall-offset-ms
                              :last-trusted-ms :confirmed-mono-ns]))
       (valid-active-tracking? clock)))

(defn- valid-persisted-state? [v]
  (and (map? v)
       (= persisted-keys (set (keys v)))
       (= format-version (:format v))
       (nil? (sched/validate (:schedule v)))
       (string? (:zone v))
       (tz/valid-zone? (:zone v))
       (or (true? (:restore-disablesleep v))
           (false? (:restore-disablesleep v)))
       (valid-clock? (:clock v))))

(defn- read-edn-safe [s]
  (let [rdr (PushbackReader. (StringReader. s))
        reject-tag (fn [& _] (throw (ex-info "tag" {})))
        opts {:eof ::eof :readers {'inst reject-tag 'uuid reject-tag} :default reject-tag}
        v (edn/read opts rdr)]
    (when-not (= ::eof (edn/read opts rdr))
      (throw (ex-info "trailing state data" {})))
    (if (valid-persisted-state? v)
      v
      (throw (ex-info "bad state" {})))))

(defn load!
  "Load persisted state from `path`. A missing file returns nil (fresh
  install). An unreadable or invalid file is quarantined to
  `<path>.corrupt-<ms>` and nil is returned. If quarantine itself fails, nil is
  still returned so startup uses an empty schedule. Returns [state event],
  where event is :fresh, :loaded, :quarantined, or :quarantine-failed."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      [nil :fresh]
      (try
        [(read-edn-safe (slurp f)) :loaded]
        (catch Throwable _
          (let [q (str path ".corrupt-" (System/currentTimeMillis))]
            (let [[r e] (c-rename path q)]
              (if (neg? r)
                [nil :quarantine-failed]
                [nil :quarantined]))))))))
