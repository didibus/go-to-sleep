(ns gotosleep.os
  "Live OS reads: sessions, power state, clocks, boot session, system zone.
  Read-only; nothing here changes system state."
  (:require [jolt.ffi :as ffi]
            [babashka.fs :as fs]
            [gotosleep.cf :as cf]
            [gotosleep.clock :as clock]
            [gotosleep.observe :as observe]))

(ffi/load-library)

(ffi/defcfn clock-gettime-nsec-np "clock_gettime_nsec_np" [:int] :uint64)
(ffi/defcfn sysctlbyname "sysctlbyname" [:string :pointer :pointer :pointer :size_t] :int
  {:capture-native-error true})

(def clock-realtime 0)
(def clock-monotonic-raw 4)
(def clock-uptime-raw 8)

(defn mono-ns
  "CLOCK_MONOTONIC_RAW: keeps counting through sleep, cannot be set."
  [] (clock-gettime-nsec-np clock-monotonic-raw))

(defn awake-ns
  "CLOCK_UPTIME_RAW: stops while the system sleeps."
  [] (clock-gettime-nsec-np clock-uptime-raw))

(defn wall-ms
  "The system wall clock, which the user can set. Never trusted directly."
  [] (quot (clock-gettime-nsec-np clock-realtime) 1000000))

(defn sysctl-string [name]
  (ffi/with-alloc [len 8]
    (ffi/write len :uint64 0 0)
    (let [[rc _] (sysctlbyname name ffi/null len ffi/null 0)
          n (ffi/read len :uint64 0)]
      (when (and (zero? rc) (pos? n))
        (ffi/with-alloc [buf n]
          (let [[rc _] (sysctlbyname name buf len ffi/null 0)]
            (when (zero? rc) (ffi/ptr->string buf))))))))

(defn boot-session [] (sysctl-string "kern.bootsessionuuid"))

(defn sessions
  "Parsed IOConsoleUsers, or :read-failed."
  []
  (try (observe/parse-console-users (cf/root-property "IOConsoleUsers"))
       (catch Throwable _ :read-failed)))

(defn capabilities []
  (try (cf/service-property "IOPMrootDomain" "System Capabilities")
       (catch Throwable _ nil)))

(defn sleep-disabled?
  "IOPMrootDomain SleepDisabled, or nil when unreadable."
  []
  (try (let [v (cf/service-property "IOPMrootDomain" "SleepDisabled")]
         (when (boolean? v) v))
       (catch Throwable _ nil)))

(defn system-zone
  "The zone id /etc/localtime points at, or nil. The process TZ is ignored."
  []
  (try (observe/zone-from-link (str (fs/read-link "/etc/localtime")))
       (catch Throwable _ nil)))

(defn ntp-server
  "The first configured NTP server, or the default when ntp.conf cannot
  be read or contains no server line."
  []
  (clock/ntp-server
   (try (slurp "/etc/ntp.conf")
        (catch Throwable _ nil))))
