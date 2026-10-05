(ns gotosleep.observe-test
  (:require [clojure.test :refer [deftest is testing]]
            [gotosleep.observe :as o]))

(def dict {"kCGSSessionUserIDKey" 503 "kCGSSessionUserNameKey" "me"
           "kCGSSessionOnConsoleKey" true "kCGSessionLoginDoneKey" true})

(deftest parse-console-users
  (testing "normal session, missing lock key means unlocked"
    (is (= [{:uid 503 :user "me" :on-console? true :login-done? true :locked? false}]
           (o/parse-console-users [dict]))))
  (testing "locked"
    (is (true? (:locked? (first (o/parse-console-users [(assoc dict "CGSSessionScreenIsLocked" true)]))))))
  (testing "empty list is the login window, not a failure"
    (is (= [] (o/parse-console-users []))))
  (testing "read failures"
    (is (= :read-failed (o/parse-console-users nil)))
    (is (= :read-failed (o/parse-console-users {"a" 1})))
    (is (= :read-failed (o/parse-console-users "x")))
    (is (= :read-failed (o/parse-console-users [(dissoc dict "kCGSSessionOnConsoleKey")])))
    (is (= :read-failed (o/parse-console-users [(assoc dict "kCGSSessionUserIDKey" "503")])))
    (is (= :read-failed (o/parse-console-users [(assoc dict "kCGSSessionOnConsoleKey" 1)])))
    (is (= :read-failed (o/parse-console-users [(assoc dict "kCGSessionLoginDoneKey" 1)])))
    (is (= :read-failed (o/parse-console-users [(assoc dict "CGSSessionScreenIsLocked" 1)])))
    (is (= :read-failed (o/parse-console-users [dict 7])))))

(deftest console-session
  (let [a {:uid 1 :on-console? false} b {:uid 2 :on-console? true}]
    (is (= b (o/console-session [a b])))
    (is (nil? (o/console-session [a])))
    (is (nil? (o/console-session :read-failed)))))

(deftest full-wake
  (is (o/full-wake? 15))
  (is (o/full-wake? 2))
  (is (not (o/full-wake? 9)))   ; cpu + network, no graphics = dark wake
  (is (o/full-wake? nil)))

(deftest zone-from-link
  (is (= "America/Los_Angeles" (o/zone-from-link "/var/db/timezone/zoneinfo/America/Los_Angeles")))
  (is (= "UTC" (o/zone-from-link "/usr/share/zoneinfo/UTC")))
  (is (= "America/Argentina/Buenos_Aires"
         (o/zone-from-link "/var/db/timezone/zoneinfo/America/Argentina/Buenos_Aires")))
  (is (nil? (o/zone-from-link "/etc/whatever")))
  (is (nil? (o/zone-from-link "/var/db/timezone/zoneinfo/../../etc/passwd")))
  (is (nil? (o/zone-from-link nil))))
