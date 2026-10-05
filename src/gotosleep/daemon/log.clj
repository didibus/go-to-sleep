(ns gotosleep.daemon.log
  "The daemon's own log: millisecond-stamped lines, rotated to .1 at 10 MB.
  Enforcement never depends on logging, so failures are swallowed."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.time Instant]))

(def ^:private max-bytes (* 10 1024 1024))

(defn- rotate! [^File f]
  (when (> (.length f) max-bytes)
    (let [one (io/file (str (.getPath f) ".1"))]
      (when (.exists one) (.delete one))
      (.renameTo f one))))

(defn append!
  "Append one log line: `<epoch-ms> <LEVEL> <event> <data-edn>`."
  [path level event data]
  (try
    (let [f (io/file path)]
      (io/make-parents f)
      (rotate! f)
      (spit f (str (.toEpochMilli (Instant/now)) " "
                   (name level) " " (name event) " " (pr-str data) "\n")
            :append true))
    (catch Throwable _ nil)))
