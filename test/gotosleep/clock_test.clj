(ns gotosleep.clock-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [gotosleep.clock :as clock]))

(def ms-per-hour (* 60 60 1000))
(def ns-per-ms 1000000)

;; A helper: readings at some monotonic point. mono in ns, wall in ms.
(defn secs->ns [s] (* s 1000000000))

(deftest trusted-now-tracks-monotonic
  (let [anchor {:wall-ms 1000000 :mono-ns (secs->ns 100)}]
    (is (= 1000000 (clock/trusted-now anchor (secs->ns 100))))
    (is (= 1010000 (clock/trusted-now anchor (secs->ns 110))))   ; +10 s
    (is (= 999000 (clock/trusted-now anchor (secs->ns 99))))))    ; -1 s

(deftest start-resumes-same-boot-session
  (let [persisted {:boot-session "AAA" :wall-ms 5000 :mono-ns (secs->ns 3)
                   :wall-offset-ms 0 :last-trusted-ms 5000 :confirmed-mono-ns (secs->ns 3)}
        {:keys [clock event]} (clock/start persisted "AAA" 999999 (secs->ns 4))]
    (testing "the anchor is reused unchanged, so a kill/relaunch is invisible"
      (is (= :resumed event))
      (is (= persisted clock)))))

(deftest unknown-boot-session-is-not-same-boot-proof
  (let [persisted {:boot-session nil :wall-ms 1000000 :mono-ns (secs->ns 1000)
                   :wall-offset-ms 0 :last-trusted-ms 1000000
                   :confirmed-mono-ns (secs->ns 1000)}
        {:keys [clock event]} (clock/start persisted nil 2000000 0)]
    (is (not= :resumed event))
    (is (= 2000000 (clock/trusted-now clock 0)))))

(deftest start-fresh-install
  (let [{:keys [clock event]} (clock/start nil "AAA" 5000 (secs->ns 3))]
    (is (= :fresh event))
    (is (= 5000 (clock/trusted-now clock (secs->ns 3))))
    (testing "fresh anchor equals the system wall, so it is not suspect"
      (is (not (clock/suspect? clock 5000 (secs->ns 3)))))))

(deftest start-new-boot-keeps-forward-clock-discounted
  ;; Someone set the wall 10 h forward while we watched; we captured the offset.
  (let [true-time 1790000000000
        jump (* 10 ms-per-hour)
        persisted {:boot-session "OLD" :wall-ms true-time :mono-ns (secs->ns 1000)
                   :wall-offset-ms jump :last-trusted-ms true-time
                   :confirmed-mono-ns (secs->ns 1000)}
        ;; reboot: mono resets, wall is still 10 h ahead of the truth
        sys-wall (+ true-time jump)
        {:keys [clock event]} (clock/start persisted "NEW" sys-wall (secs->ns 5))]
    (testing "trusted time is anchored back to the true time, not the jumped wall"
      (is (= :new-boot event))
      (is (= true-time (clock/trusted-now clock (secs->ns 5)))))
    (testing "and the still-ahead wall makes the clock suspect immediately"
      (is (clock/suspect? clock sys-wall (secs->ns 5))))))

(deftest start-new-boot-ignores-backwards-step
  (let [true-time 1790000000000
        persisted {:boot-session "OLD" :wall-ms true-time :mono-ns (secs->ns 1000)
                   :wall-offset-ms 0 :last-trusted-ms true-time
                   :confirmed-mono-ns (secs->ns 1000)}
        ;; reboot with the wall set an hour behind the last trusted time
        sys-wall (- true-time ms-per-hour)
        {:keys [clock event]} (clock/start persisted "NEW" sys-wall (secs->ns 2))]
    (testing "the anchor holds at last-trusted, ignoring the backwards wall"
      (is (= :new-boot-backwards event))
      (is (= true-time (clock/trusted-now clock (secs->ns 2)))))))

(deftest suspect-ppm-threshold
  ;; Anchor confirmed at mono=0; trusted == wall at that point.
  (let [clock {:wall-ms 1000000 :mono-ns 0 :wall-offset-ms 0
               :last-trusted-ms 1000000 :confirmed-mono-ns 0}]
    (testing "within 2 s at anchor time is fine"
      (is (not (clock/suspect? clock 1001999 0)))
      (is (clock/suspect? clock 1002001 0)))
    (testing "the budget grows by 50 ppm; after 1e6 s (~11.6 d) it is 2 s + 50 s"
      (let [mono (secs->ns 1000000)
            trusted (clock/trusted-now clock mono)
            budget (+ 2000 50000)]
        (is (not (clock/suspect? clock (+ trusted budget -1) mono)))
        (is (clock/suspect? clock (+ trusted budget 1) mono))))))

(deftest confirm-two-factor
  (let [clock {:wall-ms 1000000 :mono-ns 0 :wall-offset-ms 500
               :last-trusted-ms 999000 :confirmed-mono-ns 0}
        mono (secs->ns 60)
        sys-wall (+ (clock/trusted-now clock mono) 300)]  ; system is 300 ms ahead of network
    (testing "network-minus-system offset re-anchors to sys-wall + delta"
      (let [{:keys [clock confirmed?]} (clock/confirm clock sys-wall mono -300)]
        (is confirmed?)
        (is (= (- sys-wall 300) (clock/trusted-now clock mono)))
        (is (= mono (:confirmed-mono-ns clock)))
        (is (not (clock/suspect? clock (- sys-wall 300) mono)))))
    (testing "a large offset (spoof or big skew) never re-anchors"
      (let [{c :clock confirmed? :confirmed?} (clock/confirm clock sys-wall mono (* 10 ms-per-hour))]
        (is (not confirmed?))
        (is (= clock c))))
    (testing "exactly 2 s is still accepted, 2 s + 1 ms is not"
      (is (:confirmed? (clock/confirm clock sys-wall mono 2000)))
      (is (not (:confirmed? (clock/confirm clock sys-wall mono 2001)))))))

(deftest on-persist-captures-offset
  (let [clock {:wall-ms 1000000 :mono-ns 0 :wall-offset-ms 0
               :last-trusted-ms 1000000 :confirmed-mono-ns 0}
        mono (secs->ns 10)
        trusted (clock/trusted-now clock mono)      ; 1010000
        sys-wall (+ trusted 700)
        c (clock/on-persist clock sys-wall mono)]
    (is (= 700 (:wall-offset-ms c)))
    (is (= trusted (:last-trusted-ms c)))))

(deftest ntp-server-selection
  (is (= "time.example.org" (clock/ntp-server "server time.example.org iburst\nserver b.example")))
  (is (= "time.apple.com" (clock/ntp-server "")))
  (is (= "time.apple.com" (clock/ntp-server nil)))
  (is (= "time.apple.com" (clock/ntp-server "# only comments\ndriftfile /x")))
  (is (= "1.2.3.4" (clock/ntp-server "  server   1.2.3.4  "))))

(defn- fixture [name] (slurp (io/file "test/fixtures" name)))

(deftest sntp-parsing-with-live-fixtures
  (testing "a real successful sntp line parses network-minus-system milliseconds"
    (is (= 30 (clock/parse-sntp 0 (fixture "sntp-ok.out"))))   ; +0.029690 s -> 30 ms
    (is (= 30 (clock/parse-sntp 0 "+0.029690 +/- 0.024041 time.apple.com 17.253.16.125"))))
  (testing "a negative offset"
    (is (= -1500 (clock/parse-sntp 0 "-1.500000 +/- 0.01 host 1.2.3.4"))))
  (testing "a DNS failure (exit 69) gives nil, even with stdout present"
    (is (nil? (clock/parse-sntp 69 (fixture "sntp-dns.out")))))
  (testing "non-zero exit and unparseable output give nil"
    (is (nil? (clock/parse-sntp 1 "+0.1 ...")))
    (is (nil? (clock/parse-sntp 0 "")))
    (is (nil? (clock/parse-sntp 0 "sntp: Exchange failed")))
    (is (nil? (clock/parse-sntp 0 nil)))))
