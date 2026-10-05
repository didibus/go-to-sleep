(ns gotosleep.daemon.power
  "System sleep via IOKit. This is the daemon's only power action besides
  toggling the pmset setting."
  (:require [jolt.ffi :as ffi]))

(ffi/load-library "/System/Library/Frameworks/IOKit.framework/IOKit")
(ffi/defcfn io-pm-find "IOPMFindPowerManagement" [:uint32] :uint32)
(ffi/defcfn io-pm-sleep "IOPMSleepSystem" [:uint32] :int {:capture-native-error true})
(ffi/defcfn io-close "IOServiceClose" [:uint32] :int)

(defn sleep-system!
  "Ask the system to sleep. Returns the IOReturn code (0 = success). Whether
  the request is honored depends on SleepDisabled and any assertions; the
  daemon's fallback chain escalates to logout if sleep is refused."
  []
  (let [port (io-pm-find 0)]
    (if (zero? port)
      -1
      (try (first (io-pm-sleep port))
           (finally (io-close port))))))
