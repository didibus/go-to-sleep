(ns gotosleep.observe
  "Pure parsing of OS observations. The FFI reads live in gotosleep.os;
  keeping the parsing here keeps it testable."
  (:require [clojure.string :as str]))

(defn parse-console-users
  "IOConsoleUsers (as Clojure data) -> a vector of sessions, or :read-failed.
  A missing or non-array value, or a dictionary without
  kCGSSessionOnConsoleKey, is :read-failed. That is distinct from an empty
  vector, which means the login window. A missing CGSSessionScreenIsLocked
  means unlocked."
  [v]
  (if-not (vector? v)
    :read-failed
    (let [optional-bool? (fn [d k] (or (not (contains? d k)) (boolean? (get d k))))
          parse (fn [d]
                  (when (and (map? d)
                             (boolean? (get d "kCGSSessionOnConsoleKey"))
                             (integer? (get d "kCGSSessionUserIDKey"))
                             (optional-bool? d "kCGSessionLoginDoneKey")
                             (optional-bool? d "CGSSessionScreenIsLocked"))
                    {:uid         (get d "kCGSSessionUserIDKey")
                     :user        (get d "kCGSSessionUserNameKey")
                     :on-console? (get d "kCGSSessionOnConsoleKey")
                     :login-done? (true? (get d "kCGSessionLoginDoneKey"))
                     :locked?     (true? (get d "CGSSessionScreenIsLocked"))}))
          sessions (mapv parse v)]
      (if (some nil? sessions) :read-failed sessions))))

(defn console-session
  "The on-console session of a parsed session list, or nil."
  [sessions]
  (when (vector? sessions) (first (filter :on-console? sessions))))

(def graphics-capability 2)

(defn full-wake?
  "True when the IOPMrootDomain `System Capabilities` value has the graphics
  bit. An unreadable value counts as a full wake, so enforcement is never
  suppressed by a failed read."
  [caps]
  (if (integer? caps) (pos? (bit-and caps graphics-capability)) true))

(def ^:private zone-id-re #"^[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*$")

(defn valid-zone-id? [s]
  (boolean (and (string? s) (re-matches zone-id-re s)
                (not (str/includes? s "..")))))

(defn zone-from-link
  "The zone id in an /etc/localtime link target: everything after the last
  `zoneinfo/`. Returns nil when the target has no such component or the id is
  malformed."
  [target]
  (when (string? target)
    (let [i (str/last-index-of target "zoneinfo/")]
      (when i
        (let [id (subs target (+ i (count "zoneinfo/")))]
          (when (valid-zone-id? id) id))))))
