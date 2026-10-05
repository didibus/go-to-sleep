(ns gotosleep.agent.relock
  "The agent's best-effort secondary relock. The root daemon remains the
  authority; this path only adds a per-session fallback."
  (:require [gotosleep.cf :as cf]
            [jolt.ffi :as ffi]))

(ffi/load-library "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
(ffi/defcfn cg-session-copy-current-dictionary
  "CGSessionCopyCurrentDictionary" [] :pointer)

(ffi/load-library "/System/Library/PrivateFrameworks/login.framework/Versions/Current/login")
(ffi/defcfn sac-lock-screen "SACLockScreenImmediate" [] :int)

(defn current-session
  "Return CGSessionCopyCurrentDictionary as plain data, or nil. Copy ownership
  is balanced here."
  []
  (let [p (cg-session-copy-current-dictionary)]
    (when-not (ffi/null? p)
      (try (cf/cf->clj p)
           (finally (cf/cf-release p))))))

(defn session-unlocked-on-console?
  "Only exact booleans qualify; malformed or incomplete reads fail closed."
  [session]
  (and (map? session)
       (true? (get session "kCGSSessionOnConsoleKey"))
       (or (not (contains? session "CGSSessionScreenIsLocked"))
           (false? (get session "CGSSessionScreenIsLocked")))))

(defn lock-screen! [] (sac-lock-screen))
