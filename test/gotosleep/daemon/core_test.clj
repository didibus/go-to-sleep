(ns gotosleep.daemon.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [gotosleep.daemon.core :as core]
            [gotosleep.schedule :as sched]
            [gotosleep.tz :as tz]
            [gotosleep.version :as version]))

(def zone-id "Europe/Paris")
(def paris (tz/load-zone zone-id))
(defn at-zone [zone y m d hh mm]
  (* 1000 (tz/local->instant zone (+ (* 86400 (tz/days-from-civil y m d))
                                     (* 3600 hh)
                                     (* 60 mm)))))
(defn at [y m d hh mm] (at-zone paris y m d hh mm))

(def mon-night (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"}))

(defn clock-at [now] {:wall-ms now :mono-ns 0 :wall-offset-ms 0
                      :last-trusted-ms now :confirmed-mono-ns 0})

(defn mk-state [schedule now & kv]
  (merge {:schedule schedule :zone zone-id :clock (clock-at now)
          :restore-disablesleep false :zone-pending nil :sleep-refused false
          :episode nil :last-known-uid nil :active-since-mono nil
          :last-mono 0 :last-awake 0 :last-tick-awake 0
          :last-persist-awake 0 :last-sntp-mono 0 :last-supervise-awake 0
          :disablesleep-pending false :sleep-ready false :restore-sleep-pending false
          :clock-suspect-active false :failure-log-times {}
          :version version/value}
         (apply hash-map kv)))

(defn ns- [ms] (* ms 1000000))

(defn obs
  "Observations. `elapsed-ms` bumps mono/awake past the state's anchor."
  [now elapsed-ms & {:keys [sessions caps sleep-disabled jobs sys-zone wall]
                     :or {sessions [] caps 15 sleep-disabled false jobs [] sys-zone zone-id}}]
  {:mono (ns- elapsed-ms) :awake (ns- elapsed-ms) :wall (or wall now)
   :sys-zone sys-zone :sessions sessions :capabilities caps
   :sleep-disabled sleep-disabled :jobs jobs})

(def console-unlocked [{:uid 503 :on-console? true :login-done? true :locked? false}])
(def console-locked [{:uid 503 :on-console? true :login-done? true :locked? true}])
(def login-window [])

(defn job-keys [effects] (->> effects (filter #(= :job (first %))) (map second)))
(defn has-effect? [effects k] (some #(= k (first %)) effects))
(defn log-count [effects event]
  (count (filter #(and (= :log (first %)) (= event (nth % 2))) effects)))

(defn growth-roundtrip [state candidate observation]
  (let [dry (core/handle state
                         {:op :set-schedule :schedule candidate
                          :dry-run true :edit-mode :growth-only}
                         0 observation)
        token (get-in dry [:response :confirm-growth])
        apply-result (core/handle state
                                  {:op :set-schedule :schedule candidate
                                   :edit-mode :growth-only
                                   :confirm-growth token}
                                  0 observation)]
    {:dry dry :token token :apply apply-result}))

;; --- handle ----------------------------------------------------------------

(deftest handle-status
  (let [now (at 2026 9 28 20 0)                     ; frozen (8h before Mon 23:00)
        st (mk-state mon-night now)
        {:keys [response]} (core/handle st {:op :status} 503 (obs now 0))]
    (is (:ok response))
    (is (= :frozen (:state response)))
    (is (= now (:now response)))
    (is (= zone-id (:zone response)))
    (is (false? (:clock-suspect response)))
    (is (false? (:safety-stopped response)))
    (is (= version/value (:version response)))
    (is (seq (:occurrences response)))
    (is (= "Mon 23:00–07:00" (:label (first (:occurrences response)))))))

(deftest handle-set-schedule-dry-run
  (let [now (at 2026 9 21 12 0)                      ; open
        st (mk-state mon-night now)
        s2 (assoc mon-night :wed {:start "23:00" :end "07:00"})
        {:keys [candidate response]} (core/handle st {:op :set-schedule :schedule s2 :dry-run true} 0 (obs now 0))]
    (is (nil? candidate))
    (is (:ok response))
    (is (false? (:applied response)))))

(deftest handle-set-schedule-apply
  (let [now (at 2026 9 21 12 0)
        st (mk-state mon-night now)
        s2 (assoc mon-night :wed {:start "23:00" :end "07:00"})
        {:keys [candidate response effects]} (core/handle st {:op :set-schedule :schedule s2} 0 (obs now 0))]
    (is (= s2 (:schedule candidate)))
    (is (:applied response))
    (is (= :open (:edit-mode response)))
    (is (= s2 (get-in response [:status :schedule])))
    (is (has-effect? effects :log))))

(deftest handle-set-schedule-confirm-required
  (let [now (at 2026 9 28 20 0)
        st (mk-state sched/empty-schedule now)
        r1 (core/handle st {:op :set-schedule :schedule mon-night} 0 (obs now 0))]
    (is (nil? (:candidate r1)))
    (is (= :confirm-required (get-in r1 [:response :error :code])))
    (let [fz (get-in r1 [:response :error :freezes-now])
          r2 (core/handle st {:op :set-schedule :schedule mon-night
                              :confirm-freeze (mapv #(select-keys % [:day :start :end]) fz)} 0 (obs now 0))]
      (is (= mon-night (:schedule (:candidate r2))))
      (is (:applied (:response r2)))
      (testing "the applied immediate freeze rejects an identical no-op"
        (let [r3 (core/handle (:candidate r2)
                              {:op :set-schedule :schedule mon-night :dry-run true}
                              0 (obs now 0))]
          (is (= :growth-only (get-in r3 [:response :error :code])))
          (is (nil? (:candidate r3)))
          (is (empty? (:effects r3))))))))

(deftest dry-run-reports-daemon-confirmation-deadline
  (let [now (at 2026 9 28 20 0)
        st (mk-state sched/empty-schedule now)
        response (:response (core/handle st {:op :set-schedule :schedule mon-night :dry-run true}
                                         0 (obs now 0)))]
    (is (:ok response))
    (is (= "Tue 07:00" (:confirm-until-label response)))))

(deftest handle-set-schedule-global-growth-only
  (let [now (at 2026 9 28 20 0)
        st (mk-state mon-night now)
        observation (obs now 0)
        grow (assoc mon-night :mon {:start "22:00" :end "08:00"})
        {:keys [dry token apply]} (growth-roundtrip st grow observation)]
    (testing "dry run returns canonical authority and candidate confirmation data"
      (is (nil? (:candidate dry)))
      (is (empty? (:effects dry)))
      (is (= :growth-only (get-in dry [:response :edit-mode])))
      (is (= mon-night (:baseline token)))
      (is (= grow (:candidate token)))
      (is (= "Europe/Paris" (:zone token)))
      (is (= (at 2026 9 29 7 0) (:authority-unfrozen-at token)))
      (is (= (at 2026 9 29 8 0) (:confirm-until-at token)))
      (is (= "Tue 08:00" (get-in dry [:response :confirm-until-label])))
      (is (false? (get-in dry [:response :activates-now]))))
    (testing "missing or mismatched confirmation returns a replacement token without effects"
      (doseq [request [{:op :set-schedule :schedule grow}
                       {:op :set-schedule :schedule grow :confirm-growth (assoc token :zone "UTC")}]]
        (let [{:keys [candidate response effects]} (core/handle st request 0 observation)]
          (is (nil? candidate))
          (is (empty? effects))
          (is (= :confirm-required (get-in response [:error :code])))
          (is (= token (get-in response [:error :confirm-growth])))
          (is (= "Tue 08:00" (get-in response [:error :confirm-until-label]))))))
    (testing "exact confirmation produces one candidate, one log, and complete post-apply status"
      (is (= grow (get-in apply [:candidate :schedule])))
      (is (= 1 (log-count (:effects apply) :schedule-set)))
      (is (:applied (:response apply)))
      (is (= :growth-only (get-in apply [:response :edit-mode])))
      (is (= grow (get-in apply [:response :status :schedule])))
      (is (= :frozen (get-in apply [:response :status :state])))
      (is (= (at 2026 9 29 8 0) (get-in apply [:response :status :unfrozen-at]))))
    (testing "every prohibited or stale request is effect-free"
      (doseq [[expected request]
              [[:growth-only {:op :set-schedule :schedule mon-night :dry-run true}]
               [:growth-only {:op :set-schedule :schedule sched/empty-schedule :dry-run true}]
               [:growth-only {:op :set-schedule
                              :schedule (assoc mon-night :mon {:start "23:01" :end "07:00"})
                              :dry-run true}]
               [:invalid-schedule {:op :set-schedule :schedule {:mon {:start "x" :end "y"}}
                                   :dry-run true}]
               [:window {:op :set-schedule
                         :schedule (assoc mon-night :tue {:start "12:00" :end "13:00"})
                         :dry-run true}]
               [:stale-edit-mode {:op :set-schedule :schedule grow
                                  :dry-run true :edit-mode :open}]
               [:stale-confirmation {:op :set-schedule :schedule grow
                                     :confirm-freeze []}]]]
        (let [{:keys [candidate response effects]} (core/handle st request 0 observation)]
          (is (= expected (get-in response [:error :code])) (pr-str request))
          (is (nil? candidate) (pr-str request))
          (is (empty? effects) (pr-str request)))))))

(deftest handle-set-schedule-validates-while-open
  (let [now (at 2026 9 21 12 0)
        st (mk-state mon-night now)]
    (is (= :invalid-schedule
           (get-in (core/handle st
                                {:op :set-schedule :schedule {:mon {:start "x" :end "y"}}}
                                0 (obs now 0))
                   [:response :error :code])))))

(deftest confirmation-crossing-current-freeze-boundary-is-rejected
  (let [freeze-start (at 2026 9 28 15 0)
        before (dec freeze-start)
        st (mk-state mon-night before)
        grow (assoc mon-night :mon {:start "22:00" :end "07:00"})
        dry (:response (core/handle st {:op :set-schedule :schedule grow :dry-run true}
                                    0 (obs before 0)))
        confirm (core/handle st
                             {:op :set-schedule :schedule grow
                              :confirm-freeze (get dry :freezes-now)}
                             0 (obs before 1))]
    (is (:ok dry))
    (is (seq (:freezes-now dry)))
    (is (= :stale-confirmation (get-in confirm [:response :error :code])))
    (is (nil? (:candidate confirm)))
    (is (empty? (:effects confirm)))))

(deftest growth-only-mode-and-confirmation-precedence
  (let [frozen-now (at 2026 9 28 20 0)
        open-now (at 2026 9 29 7 0)
        grow (assoc mon-night :mon {:start "22:00" :end "08:00"})
        frozen (mk-state mon-night frozen-now)
        dry (:response (core/handle frozen
                                    {:op :set-schedule :schedule grow :dry-run true}
                                    0 (obs frozen-now 0)))
        token (:confirm-growth dry)]
    (is (= :stale-edit-mode
           (get-in (core/handle frozen
                                {:op :set-schedule :schedule grow
                                 :edit-mode :open :confirm-growth token}
                                0 (obs frozen-now 0))
                   [:response :error :code])))
    (let [open (mk-state mon-night open-now)]
      (is (= :stale-edit-mode
             (get-in (core/handle open
                                  {:op :set-schedule :schedule grow
                                   :edit-mode :growth-only :confirm-growth token}
                                  0 (obs open-now 0))
                     [:response :error :code])))
      (is (= :stale-confirmation
             (get-in (core/handle open
                                  {:op :set-schedule :schedule grow
                                   :confirm-growth token}
                                  0 (obs open-now 0))
                     [:response :error :code]))))))

(deftest active-growth-materializes-baseline-anchor-and-preserves-episode
  (let [now (at 2026 9 28 23 30)
        episode {:uid 503 :elapsed 1234 :last-awake 0 :last-sleep nil
                 :first-sleep nil :slept? false :last-logout {}}
        st (mk-state mon-night now :episode episode)
        grow (assoc mon-night :mon {:start "22:00" :end "08:00"})
        {:keys [apply]} (growth-roundtrip st grow (obs now 0))
        candidate (:candidate apply)
        baseline-start (at 2026 9 28 23 0)
        expected-mono (- (ns- (- now baseline-start)))]
    (is (= [:mon (tz/days-from-civil 2026 9 28)]
           (:active-occurrence-key candidate)))
    (is (= baseline-start (:active-since-trusted-ms candidate)))
    (is (= expected-mono (:active-since-mono candidate)))
    (is (= episode (:episode candidate)))
    (is (= (select-keys candidate
                        [:active-occurrence-key :active-since-mono
                         :active-since-trusted-ms])
           (select-keys (:clock candidate)
                        [:active-occurrence-key :active-since-mono
                         :active-since-trusted-ms])))))

(deftest active-growth-preserves-existing-tracking-and-new-baseline-is-immediate
  (let [now (at 2026 9 28 23 30)
        key [:mon (tz/days-from-civil 2026 9 28)]
        st (mk-state mon-night now
                     :active-occurrence-key key
                     :active-since-mono -123456789
                     :active-since-trusted-ms (at 2026 9 28 23 0))
        st (update st :clock merge
                   {:active-occurrence-key key
                    :active-since-mono -123456789
                    :active-since-trusted-ms (at 2026 9 28 23 0)})
        first-growth (assoc mon-night :mon {:start "22:00" :end "08:00"})
        first (:apply (growth-roundtrip st first-growth (obs now 0)))
        candidate (:candidate first)
        second-growth (assoc first-growth :mon {:start "21:00" :end "09:00"})
        second-dry (core/handle candidate
                                {:op :set-schedule :schedule second-growth :dry-run true}
                                0 (obs now 0))]
    (is (= -123456789 (:active-since-mono candidate)))
    (is (= (at 2026 9 28 23 0) (:active-since-trusted-ms candidate)))
    (is (= first-growth
           (get-in second-dry [:response :confirm-growth :baseline])))
    (is (= :growth-only
           (get-in (core/handle candidate
                                {:op :set-schedule :schedule first-growth :dry-run true}
                                0 (obs now 0))
                   [:response :error :code])))))

(deftest frozen-growth-that-crosses-now-anchors-at-linearization-and-enforces
  (let [now (at 2026 9 28 22 30)
        st (mk-state mon-night now
                     :episode {:uid 503 :elapsed 999999 :last-awake 0
                               :last-sleep 5000 :first-sleep 5000
                               :slept? false :last-logout {503 15000}}
                     :sleep-refused true
                     :sleep-ready true)
        grow (assoc mon-night :mon {:start "22:00" :end "07:00"})
        {:keys [dry apply token]} (growth-roundtrip st grow (obs now 0))
        candidate (:candidate apply)
        ticked (core/tick candidate (obs now 0 :sessions console-unlocked))]
    (is (true? (:activates-now token)))
    (is (= "Confirming will lock the screen immediately and keep it locked until Tue 07:00."
           (get-in (core/handle st {:op :set-schedule :schedule grow} 0 (obs now 0))
                   [:response :error :message])))
    (is (= now (:active-since-trusted-ms candidate)))
    (is (= 0 (:active-since-mono candidate)))
    (is (nil? (:episode candidate)))
    (is (false? (:sleep-refused candidate)))
    (is (false? (:sleep-ready candidate)))
    (is (= :active (get-in apply [:response :status :state])))
    (is (= [:lock] (filterv keyword? (job-keys (:effects ticked)))))
    (is (nil? (:candidate dry)))))

(deftest extending-active-end-enforces-past-old-end-and-opens-at-new-end
  (let [now (at 2026 9 28 23 30)
        st (mk-state mon-night now)
        grow (assoc mon-night :mon {:start "23:00" :end "08:00"})
        candidate (:candidate (:apply (growth-roundtrip st grow (obs now 0))))
        old-ended (at 2026 9 29 7 30)
        elapsed (- old-ended now)
        mono (ns- elapsed)
        advanced (assoc candidate
                        :clock (merge (:clock candidate)
                                      {:wall-ms old-ended :mono-ns mono
                                       :last-trusted-ms old-ended
                                       :confirmed-mono-ns mono}))
        still-active (core/tick advanced
                                (assoc (obs old-ended 0 :sessions console-unlocked)
                                       :mono mono :awake mono))
        new-end (at 2026 9 29 8 0)
        end-mono (ns- (- new-end now))
        ended-state (assoc (:state still-active)
                           :clock (merge (:clock (:state still-active))
                                         {:wall-ms new-end :mono-ns end-mono
                                          :last-trusted-ms new-end
                                          :confirmed-mono-ns end-mono}))
        ended (core/tick ended-state
                         (assoc (obs new-end 0 :sessions console-unlocked)
                                :mono end-mono :awake end-mono))]
    (is (= :active (:state (core/status-response advanced
                                                 (assoc (obs old-ended 0)
                                                        :mono mono :awake mono)))))
    (is (some #(and (= :job (first %)) (= :lock (second %)))
              (:effects still-active)))
    (is (= :open (:state (core/status-response (:state ended)
                                               (assoc (obs new-end 0)
                                                      :mono end-mono :awake end-mono)))))
    (is (empty? (filter #(and (= :job (first %)) (= :lock (second %)))
                        (:effects ended))))))

(deftest active-growth-anchor-survives-same-and-new-boot-restart
  (let [now (at 2026 9 28 23 30)
        boot-clock (assoc (clock-at now) :boot-session "BOOT")
        st (assoc (mk-state mon-night now) :clock boot-clock)
        grow (assoc mon-night :mon {:start "22:00" :end "08:00"})
        candidate (:candidate (:apply (growth-roundtrip st grow (obs now 0))))
        persisted (core/prepare-persist candidate (obs now 0))
        later (at 2026 9 29 0 30)
        later-mono (ns- (- later now))
        later-obs (assoc (obs later 0 :sessions console-locked)
                         :mono later-mono :awake later-mono)
        same (:state (core/init persisted "BOOT" later-obs))
        new-obs (obs later 0 :sessions console-locked)
        new (:state (core/init persisted "NEW" new-obs))]
    (is (= (at 2026 9 28 23 0) (:active-since-trusted-ms same)))
    (is (= (- (ns- (* 30 60 1000))) (:active-since-mono same)))
    (is (= (at 2026 9 28 23 0) (:active-since-trusted-ms new)))
    (is (= (- (ns- (* 90 60 1000))) (:active-since-mono new)))
    (is (= grow (:schedule same)))
    (is (= grow (:schedule new)))))

(deftest growth-while-safety-stopped-does-not-resume-enforcement
  (let [la-zone (tz/load-zone "America/Los_Angeles")
        zone "America/Los_Angeles"
        before (assoc sched/empty-schedule :sat {:start "03:00" :end "02:30"})
        grow (assoc before :sat {:start "03:00" :end "02:59"})
        date (tz/days-from-civil 2026 10 31)
        start (at-zone la-zone 2026 10 31 3 0)
        now (+ start sched/day-ms)
        day-ns (ns- sched/day-ms)
        key [:sat date]
        st (-> (mk-state before now
                         :zone zone
                         :active-occurrence-key key
                         :active-since-mono 0
                         :active-since-trusted-ms start)
               (assoc :clock {:boot-session "BOOT" :wall-ms now :mono-ns day-ns
                              :wall-offset-ms 0 :last-trusted-ms now
                              :confirmed-mono-ns day-ns
                              :active-occurrence-key key
                              :active-since-mono 0
                              :active-since-trusted-ms start}))
        observation (assoc (obs now 0 :sessions console-unlocked :sys-zone zone)
                           :mono day-ns :awake day-ns)
        candidate (:candidate (:apply (growth-roundtrip st grow observation)))
        ticked (core/tick candidate observation)]
    (is (= start (:active-since-trusted-ms candidate)))
    (is (= 0 (:active-since-mono candidate)))
    (is (true? (:safety-stopped (core/status-response candidate observation))))
    (is (empty? (filter #(and (= :job (first %))
                              (#{:lock :logout :disablesleep :restore-sleep} (second %)))
                        (:effects ticked))))
    (is (not (has-effect? (:effects ticked) :sleep-system)))))

(deftest handle-authorization
  (let [now (at 2026 9 21 12 0)
        st (mk-state mon-night now)
        o (obs now 0 :sessions console-unlocked)]
    (testing "root is allowed"
      (is (:candidate (core/handle st {:op :set-schedule :schedule mon-night} 0 o))))
    (testing "the console user is allowed"
      (is (map? (core/handle st {:op :set-schedule :schedule mon-night} 503 o))))
    (testing "a non-root, non-console peer is forbidden"
      (is (= :forbidden (get-in (core/handle st {:op :set-schedule :schedule mon-night} 999 o)
                                [:response :error :code])))
      (is (= :forbidden (get-in (core/handle st {:op :uninstall} 999 o)
                                [:response :error :code]))))
    (testing "authorization precedes the global freeze"
      (let [frozen-now (at 2026 9 28 20 0)
            frozen (mk-state mon-night frozen-now)]
        (is (= :forbidden
               (get-in (core/handle frozen
                                    {:op :set-schedule :schedule sched/empty-schedule}
                                    999 (obs frozen-now 0 :sessions console-unlocked))
                       [:response :error :code])))))
    (testing "failed session observation authorizes root only"
      (let [failed (obs now 0 :sessions :read-failed)]
        (is (:candidate
             (core/handle st {:op :set-schedule :schedule mon-night} 0 failed)))
        (is (= :forbidden
               (get-in (core/handle st {:op :set-schedule :schedule mon-night} 503 failed)
                       [:response :error :code])))))))

(deftest handle-uninstall
  (testing "open state: emits [:uninstall]"
    (let [now (at 2026 9 21 12 0) st (mk-state mon-night now)
          {:keys [response effects]} (core/handle st {:op :uninstall} 0 (obs now 0))]
      (is (:ok response))
      (is (has-effect? effects :uninstall))))
  (testing "frozen and active states: refused"
    (doseq [now [(at 2026 9 28 20 0) (at 2026 9 28 23 0)]]
      (let [st (mk-state mon-night now)
            {:keys [response effects]} (core/handle st {:op :uninstall} 0 (obs now 0))]
        (is (= :frozen (get-in response [:error :code])))
        (is (= :uninstall (get-in response [:error :operation])))
        (is (= (at 2026 9 29 7 0) (get-in response [:error :unfrozen-at])))
        (is (= "Go To Sleep cannot be uninstalled until Tue 07:00."
               (get-in response [:error :message])))
        (is (not (has-effect? effects :uninstall)))))))

(deftest handle-bad-request
  (let [now (at 2026 9 21 12 0) st (mk-state mon-night now)]
    (is (= :bad-request (get-in (core/handle st {:op :nope} 0 (obs now 0)) [:response :error :code])))))

;; --- enforcement -----------------------------------------------------------

(def block-start (at 2026 9 28 23 0))

(deftest enforce-locks-unlocked-console
  (let [st (mk-state mon-night block-start)
        {:keys [effects]} (core/tick st (obs block-start 0 :sessions console-unlocked))]
    (is (= [:lock] (job-keys effects)))
    (is (= {:uid 503} (nth (first (filter #(= :job (first %)) effects)) 2)))))

(deftest enforce-nothing-when-locked-or-loginwindow-or-inactive
  (testing "locked console: no lock job"
    (let [st (mk-state mon-night block-start)
          {:keys [effects]} (core/tick st (obs block-start 0 :sessions console-locked))]
      (is (empty? (filter #(and (= :job (first %)) (= :lock (second %))) effects)))))
  (testing "login window: nothing"
    (let [st (mk-state mon-night block-start)
          {:keys [effects]} (core/tick st (obs block-start 0 :sessions login-window))]
      (is (empty? (filter #(= :job (first %)) (remove #(and (vector? (second %))) effects))))))
  (testing "outside any block: no lock"
    (let [now (at 2026 9 21 12 0) st (mk-state mon-night now)
          {:keys [effects]} (core/tick st (obs now 0 :sessions console-unlocked))]
      (is (empty? (filter #(= :lock (second %)) effects))))))

(deftest enforce-read-failed-targets-last-known-uid
  (let [st (mk-state mon-night block-start :last-known-uid 503)
        {:keys [effects]} (core/tick st (obs block-start 0 :sessions :read-failed))]
    (is (= {:uid 503} (some #(when (= :lock (second %)) (nth % 2)) effects)))))

(deftest enforce-read-failed-without-uid-never-emits-nil-uid-job
  (let [st (mk-state mon-night block-start)
        first-tick (core/tick st (obs block-start 0 :sessions :read-failed))
        five-seconds (core/tick (:state first-tick)
                                (obs block-start 5000 :sessions :read-failed))
        effects (concat (:effects first-tick) (:effects five-seconds))]
    (is (empty? (filter #(and (= :job (first %)) (= :lock (second %))) effects)))
    (is (has-effect? (:effects five-seconds) :sleep-system)
        "the UID-less observation suppresses only the lock, not fallback sleep")))

(deftest enforce-dark-wake-emits-nothing
  (let [st (mk-state mon-night block-start)
        {:keys [effects state]} (core/tick st (obs block-start 0 :sessions console-unlocked :caps 9))]
    (is (empty? (filter #(= :job (first %)) effects)))
    (is (not (has-effect? effects :sleep-system)))))

(deftest safety-stop-after-24h-active
  (let [day-ns (ns- (* 24 60 60 1000))
        active-key [:mon (tz/days-from-civil 2026 9 28)]
        ;; anchor clock at mono=day-ns so trusted-now stays inside the block
        st (-> (mk-state mon-night block-start
                         :active-occurrence-key active-key
                         :active-since-mono 0
                         :active-since-trusted-ms block-start)
               (assoc :clock {:wall-ms block-start :mono-ns day-ns :wall-offset-ms 0
                              :last-trusted-ms block-start :confirmed-mono-ns day-ns}))
        stopped-obs (assoc (obs block-start 0 :sessions console-unlocked)
                           :mono day-ns :awake day-ns)
        {:keys [effects]} (core/tick st stopped-obs)]
    (is (some #(and (= :log (first %)) (= :safety-stop (nth % 2))) effects))
    (is (true? (:safety-stopped
                (core/status-response st
                                      (assoc (obs block-start 0 :sessions console-unlocked)
                                             :mono day-ns :awake day-ns)))))
    (testing "no enforcement effects (lock/sleep/logout), though supervision/sntp may run"
      (is (empty? (filter #(and (= :job (first %))
                                (#{:lock :logout :disablesleep :restore-sleep} (second %)))
                          effects)))
      (is (not (has-effect? effects :sleep-system)))
      (is (some #(and (= :job (first %)) (= :sntp (second %))) effects))
      (is (some #(and (= :job (first %))
                      (vector? (second %))
                      (= :agent-print (first (second %))))
                effects))
      (is (has-effect? effects :persist)))
    (testing "safety-stopped active remains growth-only"
      (let [{:keys [candidate response effects]}
            (core/handle st {:op :set-schedule :schedule sched/empty-schedule}
                         0 stopped-obs)]
        (is (= :growth-only (get-in response [:error :code])))
        (is (= :set-schedule (get-in response [:error :operation])))
        (is (nil? candidate))
        (is (empty? effects))))))

(deftest safety-stop-recovers-after-active-state-ends
  (let [day-ns (ns- sched/day-ms)
        active-key [:mon (tz/days-from-civil 2026 9 28)]
        stopped-state (-> (mk-state mon-night block-start
                                    :active-occurrence-key active-key
                                    :active-since-mono 0
                                    :active-since-trusted-ms block-start)
                          (assoc :clock {:wall-ms block-start :mono-ns day-ns :wall-offset-ms 0
                                         :last-trusted-ms block-start :confirmed-mono-ns day-ns}))
        stopped (core/tick stopped-state
                           (assoc (obs block-start 0 :sessions console-unlocked)
                                  :mono day-ns :awake day-ns))
        after (at 2026 9 29 8 0)
        after-mono (+ day-ns (ns- 1000))
        ended-state (assoc (:state stopped)
                           :clock {:wall-ms after :mono-ns after-mono :wall-offset-ms 0
                                   :last-trusted-ms after :confirmed-mono-ns after-mono})
        ended (core/tick ended-state
                         (assoc (obs after 0 :sessions console-unlocked)
                                :mono after-mono :awake after-mono))
        next-start (at 2026 10 5 23 0)
        next-mono (+ after-mono (ns- 1000))
        next-state (assoc (:state ended)
                          :clock {:wall-ms next-start :mono-ns next-mono :wall-offset-ms 0
                                  :last-trusted-ms next-start :confirmed-mono-ns next-mono})
        next-active (core/tick next-state
                               (assoc (obs next-start 0 :sessions console-unlocked)
                                      :mono next-mono :awake next-mono))]
    (is (nil? (:active-since-mono (:state ended))))
    (is (= [:lock] (filterv keyword? (job-keys (:effects next-active))))
        "a later block returns to normal lock enforcement")))

(deftest safety-stop-intentionally-cuts-off-near-25-hour-fall-back-occurrence
  (let [la (tz/load-zone "America/Los_Angeles")
        sch (assoc sched/empty-schedule :sat {:start "03:00" :end "02:59"})
        date (tz/days-from-civil 2026 10 31)
        start (at-zone la 2026 10 31 3 0)
        now (+ start sched/day-ms)
        day-ns (ns- sched/day-ms)
        st (-> (mk-state sch now
                         :zone "America/Los_Angeles"
                         :active-occurrence-key [:sat date]
                         :active-since-mono 0
                         :active-since-trusted-ms start)
               (assoc :clock {:wall-ms now :mono-ns day-ns :wall-offset-ms 0
                              :last-trusted-ms now :confirmed-mono-ns day-ns}))
        occurrence (first (:active (sched/state sch la now)))
        {:keys [effects]} (core/tick st
                                    (assoc (obs now 0 :sessions console-unlocked
                                                :sys-zone "America/Los_Angeles")
                                           :mono day-ns :awake day-ns))]
    (is occurrence)
    (is (= (- (* 25 sched/hour-ms) sched/minute-ms)
           (- (:end occurrence) (:start occurrence))))
    (is (some #(and (= :log (first %)) (= :safety-stop (nth % 2))) effects))
    (is (empty? (filter #(and (= :job (first %))
                              (or (= :lock (second %))
                                  (= :logout (second %))
                                  (= :disablesleep (second %))
                                  (= :restore-sleep (second %))))
                        effects)))
    (is (not (has-effect? effects :sleep-system)))))

(deftest a-later-occurrence-never-inherits-an-old-safety-stop
  (let [old-key [:mon (tz/days-from-civil 2026 9 28)]
        next-start (at 2026 10 5 23 0)
        next-mono (+ (ns- sched/day-ms) (ns- 1000))
        stale (-> (mk-state mon-night next-start
                            :active-occurrence-key old-key
                            :active-since-mono 0
                            :active-since-trusted-ms block-start)
                  (assoc :clock {:wall-ms next-start :mono-ns next-mono :wall-offset-ms 0
                                 :last-trusted-ms next-start :confirmed-mono-ns next-mono}))
        {:keys [state effects]} (core/tick stale
                                            (assoc (obs next-start 0 :sessions console-unlocked)
                                                   :mono next-mono :awake next-mono))]
    (is (= [:mon (tz/days-from-civil 2026 10 5)] (:active-occurrence-key state)))
    (is (= next-mono (:active-since-mono state)))
    (is (= next-start (:active-since-trusted-ms state)))
    (is (some #(and (= :job (first %)) (= :lock (second %))) effects))
    (is (not (some #(and (= :log (first %)) (= :safety-stop (nth % 2))) effects)))))

(deftest safety-stop-survives-a-new-boot-in-the-same-long-occurrence
  (let [la (tz/load-zone "America/Los_Angeles")
        date (tz/days-from-civil 2026 10 31)
        sch (assoc sched/empty-schedule :sat {:start "03:00" :end "02:59"})
        start (at-zone la 2026 10 31 3 0)
        now (+ start sched/day-ms)
        old-mono (ns- sched/day-ms)
        persisted {:schedule sch :zone "America/Los_Angeles" :restore-disablesleep false
                   :clock {:boot-session "OLD" :wall-ms now :mono-ns old-mono
                           :wall-offset-ms 0 :last-trusted-ms now
                           :confirmed-mono-ns old-mono
                           :active-occurrence-key [:sat date]
                           :active-since-mono 0
                           :active-since-trusted-ms start}}
        boot-obs (assoc (obs now 0 :sessions console-unlocked
                             :sys-zone "America/Los_Angeles")
                        :mono 0 :awake 0 :wall now)
        restarted (:state (core/init persisted "NEW" boot-obs))
        {:keys [state effects]} (core/tick restarted boot-obs)]
    (is (= [:sat date] (:active-occurrence-key state)))
    (is (= (- old-mono) (:active-since-mono state)))
    (is (= start (:active-since-trusted-ms state)))
    (is (true? (:safety-stopped (core/status-response state boot-obs))))
    (is (some #(and (= :log (first %)) (= :safety-stop (nth % 2))) effects))
    (is (empty? (filter #(and (= :job (first %)) (= :lock (second %))) effects)))))

;; --- fallback chain --------------------------------------------------------

(defn run-chain
  "Drive `n` one-second ticks from block start with the console unlocked.
  Returns [final-state all-effects]."
  [state0 n opts]
  (loop [state state0 t 0 acc []]
    (if (>= t n)
      [state acc]
      (let [o (merge (obs block-start (* t 1000) :sessions console-unlocked) opts)
            o (assoc o :mono (ns- (* t 1000)) :awake (ns- (* t 1000)))
            {:keys [state effects]} (core/tick state o)]
        (recur state (inc t) (conj acc [t effects]))))))

(deftest chain-waits-for-disablesleep-completion-before-durable-sleep
  (let [st (mk-state mon-night block-start)
        [waiting acc] (run-chain st 6 {:sleep-disabled true})
        at-five (second (last acc))
        result {:key :disablesleep :exit 0 :out "" :err "" :timed-out false}
        {:keys [state effects]} (core/tick waiting
                                           (obs block-start 6000 :sessions console-unlocked
                                                :sleep-disabled false :jobs [result]))
        persist-i (first (keep-indexed #(when (= :persist (first %2)) %1) effects))
        sleep-i (first (keep-indexed #(when (= :sleep-system (first %2)) %1) effects))
        repeated (core/tick state
                            (obs block-start 16000 :sessions console-unlocked
                                 :sleep-disabled false))
        repeat-persist-i (first (keep-indexed #(when (= :persist (first %2)) %1)
                                               (:effects repeated)))
        repeat-sleep-i (first (keep-indexed #(when (= :sleep-system (first %2)) %1)
                                             (:effects repeated)))]
    (is (some #(and (= :job (first %)) (= :disablesleep (second %))) at-five))
    (is (not (has-effect? at-five :sleep-system)))
    (is (:disablesleep-pending waiting))
    (is (:restore-disablesleep waiting))
    (is (:restore-disablesleep state))
    (is (not (:disablesleep-pending state)))
    (is (number? persist-i))
    (is (< persist-i sleep-i) "restore intent is durable before requesting sleep")
    (is (< repeat-persist-i repeat-sleep-i) "each retry retains the durability barrier")))

(deftest disablesleep-intent-is-persisted-before-job-and-safe-across-restart
  (let [[waiting acc] (run-chain (mk-state mon-night block-start) 6 {:sleep-disabled true})
        at-five (second (last acc))
        persist-i (first (keep-indexed #(when (= :persist (first %2)) %1) at-five))
        job-i (first (keep-indexed #(when (and (= :job (first %2))
                                               (= :disablesleep (second %2))) %1)
                                   at-five))
        persisted {:schedule sched/empty-schedule :zone zone-id
                   :restore-disablesleep (:restore-disablesleep waiting)
                   :clock (assoc (clock-at block-start) :boot-session "BOOT-A")}
        pending-end (core/tick (assoc waiting :schedule sched/empty-schedule)
                               (obs block-start 6000 :sessions [] :sleep-disabled true))
        failed {:key :disablesleep :exit 1 :out "" :err "failed" :timed-out false}
        failed-end (core/tick (:state pending-end)
                              (obs block-start 7000 :sessions [] :sleep-disabled true
                                   :jobs [failed]))
        restart-obs (obs block-start 0 :sessions [] :sleep-disabled true)
        restarted (:state (core/init persisted "BOOT-B" restart-obs))
        unchanged (core/tick restarted restart-obs)
        changed (core/tick restarted (assoc restart-obs :sleep-disabled false))]
    (is (:restore-disablesleep waiting))
    (is (< persist-i job-i) "restore intent is durable before pmset disables sleep")
    (is (:restore-disablesleep (:state pending-end))
        "an in-flight job cannot make block end discard restore intent")
    (is (:disablesleep-pending (:state pending-end)))
    (is (:restore-disablesleep (:state failed-end)))
    (is (some #(and (= :job (first %)) (= :restore-sleep (second %)))
              (:effects failed-end))
        "after the in-flight job resolves, restoration is requested idempotently")
    (is (:restore-disablesleep (:state unchanged)))
    (is (some #(and (= :job (first %)) (= :restore-sleep (second %)))
              (:effects unchanged))
        "a true restart observation cannot clear intent before a pre-crash pmset completes")
    (is (:restore-disablesleep (:state changed)))
    (is (some #(and (= :job (first %)) (= :restore-sleep (second %)))
              (:effects changed))
        "if the setting is observed changed after restart, restoration remains armed")))

(deftest failed-disablesleep-is-retried-without-sleeping
  (let [[waiting _] (run-chain (mk-state mon-night block-start) 6 {:sleep-disabled true})
        result {:key :disablesleep :exit 1 :out "" :err "failed" :timed-out false}
        {:keys [state effects]} (core/tick waiting
                                           (obs block-start 6000 :sessions console-unlocked
                                                :sleep-disabled true :jobs [result]))]
    (is (:disablesleep-pending state))
    (is (some #(and (= :job (first %)) (= :disablesleep (second %))) effects))
    (is (not (has-effect? effects :sleep-system)))))

(deftest chain-logout-at-15s-when-sleep-refused
  ;; sleep-disabled false and no real sleep => machine never sleeps => logout
  (let [st (mk-state mon-night block-start)
        [final acc] (run-chain st 17 {})
        effs (mapcat second acc)]
    (is (some #(and (= :job (first %)) (= :logout (second %))) effs))
    (is (:sleep-refused final))))

(deftest sleep-detected-on-dark-wake-prevents-logout
  (let [[sleeping _] (run-chain (mk-state mon-night block-start) 6 {})
        gap (* 30 60 1000)
        dark-obs (assoc (obs block-start 0 :sessions console-unlocked :caps 9)
                        :mono (ns- (+ gap 5000)) :awake (ns- 5000))
        dark (core/tick sleeping dark-obs)
        [final effects]
        (loop [state (:state dark) awake-ms 6000 acc []]
          (if (> awake-ms 16000)
            [state acc]
            (let [o (assoc (obs block-start 0 :sessions console-unlocked)
                           :mono (ns- (+ gap awake-ms)) :awake (ns- awake-ms))
                  r (core/tick state o)]
              (recur (:state r) (+ awake-ms 1000) (into acc (:effects r))))))]
    (is (true? (get-in (:state dark) [:episode :slept?])))
    (is (empty? (filter #(and (= :job (first %)) (= :logout (second %))) effects)))
    (is (not (:sleep-refused final)))))

(deftest console-user-switch-starts-a-new-episode
  (let [[state _] (run-chain (mk-state mon-night block-start) 15 {})
        other [{:uid 504 :on-console? true :login-done? true :locked? false}]
        {:keys [state effects]} (core/tick state (obs block-start 15000 :sessions other))]
    (is (= 504 (get-in state [:episode :uid])))
    (is (zero? (get-in state [:episode :elapsed])))
    (is (some #(and (= :job (first %)) (= :lock (second %))) effects))
    (is (empty? (filter #(and (= :job (first %)) (= :logout (second %))) effects)))))

(deftest restore-intent-survives-failure-and-observation-and-clears-on-success
  (let [after (at 2026 9 29 8 0)
        st (mk-state mon-night after :restore-disablesleep true)
        first-tick (core/tick st (obs after 0 :sleep-disabled false))
        failed {:key :restore-sleep :exit 1 :out "" :err "failed" :timed-out false}
        retry (core/tick (:state first-tick)
                         (obs after 1000 :sleep-disabled false :jobs [failed]))
        succeeded {:key :restore-sleep :exit 0 :out "" :err "" :timed-out false}
        done (core/tick (:state retry)
                        (obs after 2000 :sleep-disabled true :jobs [succeeded]))
        observed (core/tick st (obs after 0 :sleep-disabled true))]
    (is (:restore-disablesleep (:state first-tick)))
    (is (:restore-sleep-pending (:state first-tick)))
    (is (some #(and (= :job (first %)) (= :restore-sleep (second %))) (:effects retry)))
    (is (:restore-disablesleep (:state retry)))
    (is (false? (:restore-disablesleep (:state done))))
    (is (has-effect? (:effects done) :persist))
    (is (:restore-disablesleep (:state observed)))
    (is (:restore-sleep-pending (:state observed)))
    (is (some #(and (= :job (first %)) (= :restore-sleep (second %)))
              (:effects observed)))))

;; --- zone adoption in tick -------------------------------------------------

(deftest tick-adopts-zone-when-open
  (let [now (at 2026 9 21 12 0)
        st (mk-state mon-night now)
        {:keys [state effects]} (core/tick st (obs now 0 :sys-zone "America/Los_Angeles"))]
    (is (= "America/Los_Angeles" (:zone state)))
    (is (some #(and (= :log (first %)) (= :zone-adopted (nth % 2))) effects))))

(deftest tick-defers-zone-that-breaks-window-then-adopts-after-schedule-repair
  (let [utc tz/utc
        now (at-zone utc 2026 1 1 12 0)
        spring-window (assoc sched/empty-schedule
                             :sat {:start "20:00" :end "00:00"}
                             :sun {:start "09:00" :end "10:00"})
        repaired (assoc spring-window :sun {:start "10:00" :end "11:00"})
        st (mk-state spring-window now :zone "UTC")
        deferred (core/tick st (obs now 0 :sys-zone "America/Los_Angeles"))
        repair-result (core/handle (:state deferred)
                                   {:op :set-schedule :schedule repaired}
                                   0
                                   (obs now 500 :sys-zone "America/Los_Angeles"))
        adopted (core/tick (:candidate repair-result)
                           (obs now 1000 :sys-zone "America/Los_Angeles"))]
    (is (= "UTC" (:zone (:state deferred))))
    (is (= "America/Los_Angeles" (:zone-pending (:state deferred))))
    (is (:applied (:response repair-result)))
    (is (= repaired (:schedule (:candidate repair-result))))
    (is (= "America/Los_Angeles" (:zone (:state adopted))))
    (is (nil? (:zone-pending (:state adopted))))
    (is (some #(and (= :log (first %)) (= :zone-adopted (nth % 2)))
              (:effects adopted)))))

(deftest tick-defers-zone-when-frozen-shift
  (let [now (at 2026 9 28 20 0)                       ; Mon block frozen in Paris
        st (mk-state mon-night now)
        {:keys [state]} (core/tick st (obs now 0 :sys-zone "America/Los_Angeles"))]
    (is (= zone-id (:zone state)))
    (is (= "America/Los_Angeles" (:zone-pending state)))))

(deftest tick-safely-adopts-zone-while-frozen-or-active-without-opening-mutations
  (doseq [now [(at 2026 9 28 20 0) block-start]]
    (let [st (mk-state mon-night now)
          observation (obs now 0 :sys-zone "Europe/Berlin")
          {:keys [state effects]} (core/tick st observation)
          mutation (core/handle state
                                {:op :set-schedule :schedule sched/empty-schedule}
                                0 observation)]
      (is (= "Europe/Berlin" (:zone state)))
      (is (= mon-night (:schedule state)))
      (is (some #(and (= :log (first %)) (= :zone-adopted (nth % 2))) effects))
      (is (= :growth-only (get-in mutation [:response :error :code])))
      (is (nil? (:candidate mutation))))))

(deftest unreadable-zone-preserves-pending-zone
  (let [st (mk-state mon-night block-start :zone-pending "America/Los_Angeles")
        {:keys [state]} (core/tick st (obs block-start 0 :sys-zone nil :sessions console-locked))]
    (is (= "America/Los_Angeles" (:zone-pending state)))))

(deftest tick-late-boundary-is-more-than-two-seconds
  (let [st (mk-state mon-night block-start :last-tick-awake 1)
        at-two (core/tick st (assoc (obs block-start 0 :sessions console-locked)
                                    :awake (+ 1 (ns- 2000))))
        over-two (core/tick st (assoc (obs block-start 0 :sessions console-locked)
                                      :awake (+ 2 (ns- 2000))))]
    (is (not (some #(and (= :log (first %)) (= :tick-late (nth % 2))) (:effects at-two))))
    (is (some #(and (= :log (first %)) (= :tick-late (nth % 2))) (:effects over-two)))))

(deftest structured-job-and-clock-suspect-logging
  (let [lock-result {:key :lock :exit 0 :out "uid=0\n" :err "" :timed-out false}
        locked (core/tick (mk-state mon-night block-start)
                          (obs block-start 1000 :sessions console-locked :jobs [lock-result]))
        lock-log (some #(when (and (= :log (first %)) (= :job-result (nth % 2))) %) (:effects locked))
        jump (* 10 60 60 1000)
        suspect1 (core/tick (:state locked)
                            (obs block-start 2000 :sessions console-locked
                                 :wall (+ block-start 2000 jump)))
        suspect2 (core/tick (:state suspect1)
                            (obs block-start 3000 :sessions console-locked
                                 :wall (+ block-start 3000 jump)))
        normal (core/tick (:state suspect2)
                          (obs block-start 4000 :sessions console-locked
                               :wall (+ block-start 4000)))
        suspect3 (core/tick (:state normal)
                            (obs block-start 5000 :sessions console-locked
                                 :wall (+ block-start 5000 jump)))]
    (is (= "uid=0\n" (get-in lock-log [3 :out])))
    (is (some #(and (= :log (first %)) (= :clock-suspect (nth % 2))) (:effects suspect1)))
    (is (not (some #(and (= :log (first %)) (= :clock-suspect (nth % 2))) (:effects suspect2))))
    (is (some #(and (= :log (first %)) (= :clock-suspect (nth % 2))) (:effects suspect3)))))

(deftest repeated-identical-agent-failure-is-hourly-suppressed
  (let [failure {:key [:agent-print 503] :exit 113 :out "" :err "not loaded" :timed-out false}
        first-r (core/tick (mk-state mon-night block-start)
                           (obs block-start 1000 :sessions [] :jobs [failure]))
        second-r (core/tick (:state first-r)
                            (obs block-start 2000 :sessions [] :jobs [failure]))
        hour-r (core/tick (:state second-r)
                          (obs block-start 3601001 :sessions [] :jobs [failure]))
        logged? (fn [r] (some #(and (= :log (first %)) (= :agent-job-result (nth % 2))) (:effects r)))]
    (is (logged? first-r))
    (is (not (logged? second-r)))
    (is (logged? hour-r))))

;; --- init ------------------------------------------------------------------

(deftest init-fresh
  (let [{:keys [state effects]} (core/init nil "BOOT1" (obs (at 2026 9 21 12 0) 0))]
    (is (= zone-id (:zone state)))
    (is (some #(= :clock-start (nth % 2)) effects))))

(deftest init-enforcement-gap
  ;; last trusted was during Monday's block; now is after it -> gap logged
  (let [persisted {:schedule mon-night :zone zone-id
                   :clock {:boot-session "OLD" :wall-ms (at 2026 9 29 3 0) :mono-ns (ns- 1000)
                           :wall-offset-ms 0 :last-trusted-ms (at 2026 9 29 3 0)
                           :confirmed-mono-ns (ns- 1000)}}
        now-obs (obs (at 2026 9 29 8 0) 0)
        {:keys [effects]} (core/init persisted "NEWBOOT" (assoc now-obs :mono 0 :wall (at 2026 9 29 8 0)))]
    (is (some #(and (= :log (first %)) (= :enforcement-gap (nth % 2))) effects))))

(deftest init-enforcement-gap-uses-forty-eight-hour-lookback
  (let [lt (at 2026 9 29 3 0)
        bounds (atom nil)
        persisted {:schedule mon-night :zone zone-id :restore-disablesleep false
                   :clock {:boot-session "OLD" :wall-ms lt :mono-ns 0 :wall-offset-ms 0
                           :last-trusted-ms lt :confirmed-mono-ns 0}}]
    (with-redefs [sched/occurrences (fn [_ _ from to]
                                     (reset! bounds [from to])
                                     [])]
      (core/init persisted "NEW" (obs (at 2026 9 29 8 0) 0)))
    (is (= (- lt sched/occurrence-lookback-ms) (first @bounds)))))

(deftest safety-stop-age-survives-same-boot-restart
  (let [clock (assoc (clock-at block-start) :boot-session "BOOT")
        st (assoc (mk-state mon-night block-start) :clock clock)
        started (core/tick st (obs block-start 1000 :sessions console-locked))
        persisted {:schedule mon-night :zone zone-id :restore-disablesleep false
                   :clock (:clock (:state started))}
        restarted (core/init persisted "BOOT" (obs block-start 5000 :sessions console-locked))]
    (is (= 0 (:active-since-mono (:state restarted))))
    (is (= 0 (get-in (:state restarted) [:clock :active-since-mono])))
    (is (= [:mon (tz/days-from-civil 2026 9 28)]
           (:active-occurrence-key (:state restarted))))
    (is (= block-start (:active-since-trusted-ms (:state restarted))))))
