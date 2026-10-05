(ns gotosleep.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [gotosleep.protocol :as proto]
            [gotosleep.schedule :as sched]
            [gotosleep.tz :as tz]))

(defn bytes-of [s] (.getBytes s "UTF-8"))
(def paris (tz/load-zone "Europe/Paris"))
(defn at [y m d hh mm]
  (* 1000 (tz/local->instant paris (+ (* 86400 (tz/days-from-civil y m d)) (* 3600 hh) (* 60 mm)))))
(def valid-growth-token
  {:baseline sched/empty-schedule
   :candidate (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
   :zone "Europe/Paris"
   :authority-frozen [{:day :mon :start 100 :end 200}]
   :authority-unfrozen-at 200
   :confirm-until-at 300
   :activates-now false})

(deftest utf8-validation
  (is (proto/valid-utf8? (bytes-of "{:op :status}")))
  (is (proto/valid-utf8? (bytes-of "héllo ☾ 🌙")))
  (testing "malformed sequences are rejected"
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0xff) 65]))))
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0x80)]))))          ; lone continuation
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0xc0) (unchecked-byte 0x80)])))) ; overlong
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0xe0) (unchecked-byte 0x80) (unchecked-byte 0x80)])))) ; overlong 3
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0xed) (unchecked-byte 0xa0) (unchecked-byte 0x80)])))) ; surrogate
    (is (not (proto/valid-utf8? (byte-array [(unchecked-byte 0xc2)]))))))       ; truncated

(deftest read-request-accepts-valid
  (is (= [:ok {:op :status}] (proto/read-request (bytes-of "{:op :status}"))))
  (is (= [:ok {:op :status}] (proto/read-request (bytes-of "  {:op :status}  \n"))))
  (is (= [:ok {:op :uninstall}] (proto/read-request (bytes-of "{:op :uninstall}"))))
  (doseq [request [{:op :set-schedule :schedule sched/empty-schedule}
                   {:op :set-schedule :schedule sched/empty-schedule
                    :dry-run true :edit-mode :open}
                   {:op :set-schedule :schedule sched/empty-schedule
                    :dry-run false :confirm-freeze []}
                   {:op :set-schedule :schedule (:candidate valid-growth-token)
                    :edit-mode :growth-only :confirm-growth valid-growth-token}]]
    (is (= [:ok request] (proto/read-request (bytes-of (pr-str request))))
        (pr-str request))))

(deftest read-request-rejects
  (testing "everything malformed becomes [:bad-request], no throw"
    (doseq [s ["" "   " "not-a-map" "{:op :status" "42" ":status"
               "{:op :status} {:op :status}"        ; trailing form
               "{:op :status} garbage"
               "{:op :frobnicate}"                  ; unknown op
               "{:no-op 1}"
               "{:op :status :extra true}"          ; exact operation shape
               "{:op :uninstall :dry-run true}"
               "{:op :set-schedule}"                ; schedule is required
               "{:op :set-schedule :schedule {} :dry-run :yes}"
               "{:op :set-schedule :schedule {} :confirm-freeze {}}"
               "{:op :set-schedule :schedule {} :edit-mode nil}"
               "{:op :set-schedule :schedule {} :edit-mode :locked}"
               "{:op :set-schedule :schedule {} :unknown true}"
               "#inst \"2020-01-01T00:00:00\""      ; built-in tagged literals
               "{:op :status :t #uuid \"00000000-0000-0000-0000-000000000000\"}"
               "#=(+ 1 2)"                          ; eval reader
               "#foo{:op :status}"                  ; custom tag
               "{:a 1 :a 2}"]]                      ; duplicate keys
      (is (= [:bad-request] (proto/read-request (bytes-of s))) (pr-str s)))
    (testing "oversize"
      (is (= [:bad-request]
             (proto/read-request (bytes-of (str "{:op :status :pad \"" (apply str (repeat 70000 "x")) "\"}"))))))
    (testing "deeply nested does not throw"
      (is (= [:bad-request]
             (proto/read-request (bytes-of (str (apply str (repeat 32000 "[")) "1"))))))
    (testing "invalid UTF-8 bytes"
      (is (= [:bad-request] (proto/read-request (byte-array [(unchecked-byte 0xff) 65])))))))

(deftest set-schedule-exact-shape-validation
  (let [base {:op :set-schedule :schedule sched/empty-schedule}
        bad-tokens [nil
                    1
                    {}
                    (dissoc valid-growth-token :zone)
                    (assoc valid-growth-token :zone 1)
                    (assoc valid-growth-token :baseline {})
                    (assoc valid-growth-token :authority-frozen {})
                    (assoc valid-growth-token :authority-frozen
                           [{:day :mon :start 100 :end 200 :extra true}])
                    (assoc valid-growth-token :authority-frozen
                           [{:day :mon :start "100" :end 200}])
                    (assoc valid-growth-token :authority-frozen
                           [{:day :tue :start 300 :end 400}
                            {:day :mon :start 100 :end 200}])
                    (assoc valid-growth-token :extra true)]
        bad-requests [(assoc base :dry-run true :confirm-freeze [])
                      (assoc base :dry-run true :confirm-growth valid-growth-token)
                      (assoc base :confirm-freeze [] :confirm-growth valid-growth-token)
                      (assoc base :confirm-freeze nil)
                      (assoc base :confirm-freeze [{:day :mon :start 1 :end 2 :x 3}])
                      (assoc base :confirm-freeze [{:day :nope :start 1 :end 2}])]]
    (doseq [token bad-tokens]
      (is (= [:bad-request]
             (proto/read-request
              (bytes-of (pr-str (assoc base :confirm-growth token)))))
          (pr-str token)))
    (doseq [request bad-requests]
      (is (= [:bad-request] (proto/read-request (bytes-of (pr-str request))))
          (pr-str request)))))

(deftest formatting
  (is (= "Tue 07:00" (proto/format-time paris (at 2026 9 29 7 0))))
  (is (= "Mon 23:00" (proto/format-time paris (at 2026 9 28 23 0))))
  (let [o {:day :mon :start (at 2026 9 28 23 0) :end (at 2026 9 29 7 0)}]
    (is (= "Mon 23:00–07:00" (proto/occ-label paris o)))
    (let [w (proto/occ->wire paris (at 2026 9 28 20 0) o)]
      (is (= "Mon 23:00–07:00" (:label w)))
      (is (true? (:frozen? w)))
      (is (false? (:active? w)))
      (is (= (:start o) (:start w))))))

(deftest error-messages
  (let [now (at 2026 9 28 20 0)
        msg (fn [err] (:message (:error (proto/error-response paris now err))))]
    (is (= "Unrecognised request." (msg {:code :bad-request})))
    (is (= "Only the logged-in user can change the schedule." (msg {:code :forbidden})))
    (is (= "Wednesday: a block must be at least 15 minutes."
           (msg {:code :invalid-schedule :day :wed :reason :too-short})))
    (is (= "Couldn't save the schedule; nothing changed." (msg {:code :storage})))
    (testing "frozen messages are operation-scoped"
      (let [unfrozen-at (at 2026 9 29 7 0)]
        (is (= "Schedule is frozen until Tue 07:00."
               (msg {:code :frozen :operation :set-schedule :unfrozen-at unfrozen-at})))
        (is (= "Go To Sleep cannot be uninstalled until Tue 07:00."
               (msg {:code :frozen :operation :uninstall :unfrozen-at unfrozen-at})))))
    (is (= "Schedule is frozen until Tue 07:00. Until then, blocks may only be added or made longer."
           (msg {:code :growth-only :operation :set-schedule
                 :unfrozen-at (at 2026 9 29 7 0)})))
    (is (= "The schedule state changed. Review and save again."
           (msg {:code :stale-edit-mode})))
    (is (= "The schedule mode changed. Review and save again."
           (msg {:code :stale-confirmation})))
    (testing "window states the gap in h/min"
      (is (= "Monday's block would end 8 h 30 min before Tuesday's starts; blocks need at least 9 h between them."
             (msg {:code :window :days [:mon :tue] :gap-minutes 510 :at (at 2026 9 29 7 0)}))))
    (testing "confirm-required names the unfreeze time"
      (let [fz [{:day :mon :start (at 2026 9 28 22 0) :end (at 2026 9 29 7 0)}]]
        (is (= "This takes effect immediately and can't be undone until Tue 07:00."
               (msg {:code :confirm-required :freezes-now fz})))
        (is (= "These changes cannot be undone until Tue 08:00."
               (msg {:code :confirm-required :confirm-until-at (at 2026 9 29 8 0)
                     :activates-now false})))
        (is (= "Confirming will lock the screen immediately and keep it locked until Tue 08:00."
               (msg {:code :confirm-required :confirm-until-at (at 2026 9 29 8 0)
                     :activates-now true})))))
    (testing "global frozen wire errors contain no representative occurrence"
      (let [o {:day :mon :start (at 2026 9 28 23 0) :end (at 2026 9 29 7 0)}
            resp (proto/error-response paris now {:code :frozen :operation :set-schedule
                                                  :day :mon :occurrence o
                                                  :unfrozen-at (:end o) :pair [o o]})]
        (is (nil? (:pair (:error resp))))
        (is (nil? (get-in resp [:error :day])))
        (is (nil? (get-in resp [:error :occurrence])))
        (is (= :set-schedule (get-in resp [:error :operation])))
        (is (= (:end o) (get-in resp [:error :unfrozen-at])))
        (is (= #{:code :operation :unfrozen-at :message}
               (set (keys (:error resp)))))))))
