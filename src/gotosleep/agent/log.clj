(ns gotosleep.agent.log
  "The per-user agent log. Logging is best-effort and never controls
  enforcement. Lines are `<epoch-ms> <event> <data-edn>`."
  (:require [clojure.java.io :as io]))

(defn line
  "Format one agent log line. Public so timestamp precision is headless-tested."
  [epoch-ms event data]
  (str epoch-ms " " (name event) " " (pr-str data) "\n"))

(defn append-at!
  "Append an event using the supplied epoch millisecond timestamp."
  [path epoch-ms event data]
  (try
    (let [f (io/file path)]
      (io/make-parents f)
      (spit f (line epoch-ms event data) :append true)
      true)
    (catch Throwable _ false)))

(defn append!
  "Append an event stamped with the current epoch millisecond time."
  [path event data]
  (append-at! path (System/currentTimeMillis) event data))
