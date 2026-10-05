(ns gotosleep.lock.main
  "The lock helper. The daemon launches it as root inside a user's bootstrap
  namespace (`launchctl asuser`); it prints its uid, locks the screen once via
  a private login.framework call, and exits. Kept tiny and separate so the
  daemon logs the uid it ran as and so the user can't signal it without sudo."
  (:require [jolt.ffi :as ffi]))

(ffi/load-library)
(ffi/defcfn c-getuid "getuid" [] :uint32)

;; SACLockScreenImmediate lives in the private login.framework.
(ffi/load-library "/System/Library/PrivateFrameworks/login.framework/Versions/Current/login")
(ffi/defcfn sac-lock-screen "SACLockScreenImmediate" [] :int)

(defn uid-line
  "The line the helper prints so the daemon can log the uid it ran as."
  []
  (str "uid=" (c-getuid)))

(defn lock-screen!
  "Lock the screen immediately. Returns the call's result. Only the installed
  daemon should invoke this function."
  []
  (sac-lock-screen))

(defn -main [& _]
  (println (uid-line))
  (flush)
  (lock-screen!)
  (System/exit 0))
