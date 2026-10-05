(ns gotosleep.agent.notify
  "Notification Center warnings via osascript. The notification is attributed
  to Script Editor; UNUserNotificationCenter would need a signed bundle and an
  ObjC block. Non-blocking."
  (:require [gotosleep.subprocess :as subprocess]
            [jolt.process :as p]))

(defn notify-argv
  "The osascript argv that posts one notification. Pure."
  [title body]
  ["/usr/bin/osascript" "-e"
   (str "display notification " (pr-str body) " with title " (pr-str title))])

(defn post!
  "Launch a notification without blocking. Returns true only when osascript
  was successfully spawned, so the poller can retry launch failures."
  [title body]
  (try
    (let [proc (p/process (notify-argv title body) {:out :string :err :string})]
      (subprocess/close-input! proc)
      (try
        (future
          (try
            @proc
            (catch Throwable _
              (try (p/destroy proc) (catch Throwable _ nil)))
            (finally
              (subprocess/close-pipes! proc))))
        (catch Throwable ex
          (try (p/destroy proc) (catch Throwable _ nil))
          (subprocess/close-pipes! proc)
          (throw ex)))
      true)
    (catch Throwable _ false)))
