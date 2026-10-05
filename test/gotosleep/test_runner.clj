(ns gotosleep.test-runner
  "Runs every clojure.test namespace under a directory and exits non-zero on any
  failure, error, or namespace that fails to load. Jolt has no JVM: a load
  failure must be counted by hand or it silently drops out of the run."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- nses-under [dir suffix]
  (->> (file-seq (io/file dir))
       (map #(.getPath %))
       (filter #(str/ends-with? % suffix))
       (map #(-> % (subs (inc (count dir))) (str/replace #"\.clj$" "")
                 (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

(defn -main [& [dir suffix]]
  (let [dir    (or dir "test")
        suffix (or suffix "_test.clj")
        nses   (nses-under dir suffix)
        loaded (atom [])
        broken (atom [])]
    (doseq [n nses]
      (try (require n) (swap! loaded conj n)
           (catch Throwable e
             (swap! broken conj n)
             (println "FAILED TO LOAD" n "-" (ex-message e)))))
    (let [{:keys [test fail error]} (if (seq @loaded)
                                      (apply t/run-tests @loaded)
                                      {:test 0 :fail 0 :error 0})
          bad (+ fail error (count @broken))]
      (println (format "%d namespaces (%d failed to load), %d tests, %d failures, %d errors"
                       (count nses) (count @broken) test fail error))
      (System/exit (if (and (zero? bad) (pos? test)) 0 1)))))
