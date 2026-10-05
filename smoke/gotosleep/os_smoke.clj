(ns gotosleep.os-smoke
  "Read-only headless checks of the live OS reads against the system's own
  tools."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jolt.process :as p]
            [gotosleep.os :as os]))

(defn- sh-out [& args] (str/trim (:out (apply p/sh args))))

(defn- plist-raw [file keypath]
  (let [{:keys [exit out]} (p/sh "plutil" "-extract" keypath "raw" "-o" "-" file)]
    (when (zero? exit) (str/trim out))))

(deftest sessions-match-ioreg
  (let [f "/tmp/gotosleep-smoke-ioroot.plist"
        _ (spit f (:out (p/sh "ioreg" "-n" "Root" "-d1" "-a")))
        n (parse-long (plist-raw f "IOConsoleUsers"))
        ours (os/sessions)]
    (is (vector? ours))
    (is (= n (count ours)))
    (doseq [i (range n)]
      (let [s (nth ours i)]
        (is (= (parse-long (plist-raw f (str "IOConsoleUsers." i ".kCGSSessionUserIDKey"))) (:uid s)))
        (is (= (= "true" (plist-raw f (str "IOConsoleUsers." i ".kCGSSessionOnConsoleKey"))) (:on-console? s)))
        (is (= (= "true" (plist-raw f (str "IOConsoleUsers." i ".CGSSessionScreenIsLocked"))) (:locked? s)))))))

(deftest power-reads-match-ioreg
  (let [f "/tmp/gotosleep-smoke-iopm.plist"
        _ (spit f (:out (p/sh "ioreg" "-r" "-n" "IOPMrootDomain" "-d1" "-a")))]
    (is (= (parse-long (plist-raw f "0.System Capabilities")) (os/capabilities)))
    (is (= (= "true" (plist-raw f "0.SleepDisabled")) (os/sleep-disabled?)))))

(deftest boot-session-matches-sysctl
  (is (= (sh-out "sysctl" "-n" "kern.bootsessionuuid") (os/boot-session))))

(deftest system-zone-matches-link
  (let [target (sh-out "readlink" "/etc/localtime")]
    (is (some? (os/system-zone)))
    (is (str/ends-with? target (str "zoneinfo/" (os/system-zone))))))

(deftest clocks
  (testing "monotonic raw counts at least as fast as awake time"
    (let [m1 (os/mono-ns) a1 (os/awake-ns)
          _ (Thread/sleep 200)
          m2 (os/mono-ns) a2 (os/awake-ns)]
      (is (> (- m2 m1) 150000000))
      (is (> (- a2 a1) 150000000))
      (is (>= (- m2 a2) (- (- m1 a1) 5000000)))))
  (testing "wall clock is milliseconds since the epoch"
    (is (< (Math/abs (- (os/wall-ms) (* 1000 (parse-long (sh-out "date" "+%s"))))) 2000))))
