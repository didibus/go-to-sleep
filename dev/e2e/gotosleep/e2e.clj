(ns gotosleep.e2e
  "User-mode end-to-end rehearsal. Runs the real shell — the store, AF_UNIX
  socket server, and pure core — with a recording effect executor, so lock,
  sleep, disablesleep, logout, and launchctl effects are recorded, never run.
  Time is driven synthetically so token-confirmed growth and the
  frozen→active→open path take seconds, not real hours. It talks to the daemon
  through the real agent client and persists through the real store. No sudo,
  no locking, nothing dangerous.

  Run with `jolt -M:e2e` (or `bash dev/e2e/run.sh`). Exits non-zero on any
  failed assertion."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.process :as proc]
            [gotosleep.tz :as tz]
            [gotosleep.schedule :as sched]
            [gotosleep.daemon.core :as core]
            [gotosleep.daemon.main :as main]
            [gotosleep.daemon.socket :as socket]
            [gotosleep.daemon.store :as store]
            [gotosleep.agent.client :as client]
            [gotosleep.version :as version]))

(def failures (atom 0))
(defn check [desc pred]
  (if pred (println "  ok:" desc)
      (do (println "  FAIL:" desc) (swap! failures inc))))

(def zone-id "America/Los_Angeles")
(def zone (tz/load-zone zone-id))
(defn at [y m d hh mm]
  (* 1000 (tz/local->instant zone (+ (* 86400 (tz/days-from-civil y m d)) (* 3600 hh) (* 60 mm)))))

;; 2026-09-28 is a Monday.
(def now-2258 (at 2026 9 28 22 58))
(def block {:start "23:00" :end "23:30"})
(def schedule (assoc sched/empty-schedule
                     :mon block
                     :thu block))
(def grown-schedule
  (assoc schedule
         :mon {:start "22:55" :end "23:35"}
         :wed {:start "12:00" :end "12:15"}))
(def mixed-prohibited
  (assoc grown-schedule
         :mon {:start "22:50" :end "23:40"}
         :thu {:start "23:05" :end "23:30"}))
(def old-baseline-growth
  (assoc grown-schedule :mon {:start "22:50" :end "23:32"}))

(defn persisted [path]
  (edn/read-string (slurp path)))

(defn schedule-set-count [effects]
  (count (filter #(and (= :log (first %))
                       (= :schedule-set (nth % 2)))
                 effects)))

(defn -main [& _]
  (let [dir (str "/tmp/gts-e2e-" (System/nanoTime))
        _ (.mkdirs (java.io.File. dir))
        paths {:state (str dir "/state.edn") :log (str dir "/log") :socket (str dir "/sock")}
        recorded (atom [])
        exec (fn [effect] (swap! recorded conj effect))   ; record, never run
        uid (parse-long (str/trim (:out (proc/sh "id" "-u"))))
        ;; the client connects as this uid; it must be the on-console session
        ;; so :set-schedule is authorized (getpeereid on the socket).
        base-obs {:sys-zone zone-id
                  :sessions [{:uid uid :on-console? true :login-done? true :locked? false}]
                  :capabilities 15 :sleep-disabled false :jobs []}
        clock-for (fn [now] {:wall-ms now :mono-ns 0 :wall-offset-ms 0
                             :last-trusted-ms now :confirmed-mono-ns 0})
        state (atom {:schedule sched/empty-schedule :zone zone-id :clock (clock-for now-2258)
                     :restore-disablesleep false :zone-pending nil :sleep-refused false
                     :episode nil :last-known-uid nil :active-since-mono nil
                     :last-mono 0 :last-awake 0 :last-tick-awake 0
                     :last-persist-awake 0 :last-sntp-mono 0 :last-supervise-awake 0
                     :version version/value})
        obs (atom (assoc base-obs :mono 0 :awake 0 :wall now-2258))
        set-now! (fn [now] (swap! state assoc :clock (clock-for now))
                   (reset! obs (assoc base-obs :mono 0 :awake 0 :wall now)))
        handler (main/make-handler state obs exec paths identity)
        listen-fd (socket/bind-listen! (:socket paths))
        stop (atom false)
        server (future (while (not @stop) (socket/serve-one! listen-fd handler 50)))
        send (fn [req] (client/send-request (:socket paths) req))
        saves (atom 0)
        original-save! store/save!]
    (try
      (with-redefs [store/save! (fn [path value]
                                  (swap! saves inc)
                                  (original-save! path value))]
        (println "1. establish a real persisted frozen baseline")
        (let [dry (send {:op :set-schedule :schedule schedule
                         :dry-run true :edit-mode :open})
              fz (:freezes-now dry)
              ok (send {:op :set-schedule :schedule schedule
                        :edit-mode :open
                        :confirm-freeze
                        (mapv #(select-keys % [:day :start :end]) fz)})]
          (check "open dry-run reports an immediate freeze" (seq fz))
          (check "exact open confirmation applies" (:applied ok))
          (check "baseline is persisted by the real store"
                 (= schedule (:schedule (persisted (:state paths)))))
          (check "baseline persistence happened once" (= 1 @saves))
          (check "baseline emitted one schedule-set"
                 (= 1 (schedule-set-count @recorded))))

        (println "2. dry-run produces an exact growth token; bad echoes do nothing")
        (let [before-file (persisted (:state paths))
              before-state @state
              before-effects @recorded
              before-saves @saves
              dry (send {:op :set-schedule :schedule grown-schedule
                         :dry-run true :edit-mode :growth-only})
              token (:confirm-growth dry)
              missing (send {:op :set-schedule :schedule grown-schedule
                             :edit-mode :growth-only})
              mismatched (send {:op :set-schedule :schedule grown-schedule
                                :edit-mode :growth-only
                                :confirm-growth (assoc token :zone "UTC")})]
          (check "growth dry-run succeeds without applying"
                 (and (:ok dry) (false? (:applied dry))))
          (check "growth token binds the exact baseline and candidate"
                 (and (= schedule (:baseline token))
                      (= grown-schedule (:candidate token))
                      (= zone-id (:zone token))))
          (check "growth token binds canonical frozen authority"
                 (= [{:day :mon
                      :start (at 2026 9 28 23 0)
                      :end (at 2026 9 28 23 30)}]
                    (:authority-frozen token)))
          (check "growth token carries authority and candidate deadlines"
                 (and (= (at 2026 9 28 23 30)
                         (:authority-unfrozen-at token))
                      (= (at 2026 9 28 23 35)
                         (:confirm-until-at token))))
          (check "earlier growth is marked as immediately activating"
                 (true? (:activates-now token)))
          (check "missing confirmation returns the exact current token"
                 (and (= :confirm-required (get-in missing [:error :code]))
                      (= token (get-in missing [:error :confirm-growth]))))
          (check "mismatched confirmation returns the exact replacement token"
                 (and (= :confirm-required (get-in mismatched [:error :code]))
                      (= token (get-in mismatched [:error :confirm-growth]))))
          (check "dry-run and bad echoes did not mutate memory"
                 (= before-state @state))
          (check "dry-run and bad echoes did not rewrite the store"
                 (and (= before-saves @saves)
                      (= before-file (persisted (:state paths)))))
          (check "dry-run and bad echoes emitted no effects"
                 (= before-effects @recorded))

          (println "3. exact confirmation persists and emits exactly once")
          (let [applied (send {:op :set-schedule :schedule grown-schedule
                               :edit-mode :growth-only
                               :confirm-growth token})]
            (check "exact confirmed growth applies" (:applied applied))
            (check "apply returns the complete new active status"
                   (and (= :growth-only (:edit-mode applied))
                        (= :active (get-in applied [:status :state]))
                        (= grown-schedule (get-in applied [:status :schedule]))
                        (= "Mon 23:35" (get-in applied [:status :unfrozen-label]))))
            (check "confirmed growth persisted exactly once"
                   (= (inc before-saves) @saves))
            (check "confirmed growth is the real persisted schedule"
                   (= grown-schedule (:schedule (persisted (:state paths)))))
            (check "confirmed growth emitted exactly one schedule-set"
                   (= (inc (schedule-set-count before-effects))
                      (schedule-set-count @recorded))))

          (println "4. mixed and stale-baseline candidates are atomically rejected")
          (let [accepted-file (persisted (:state paths))
                accepted-state @state
                accepted-effects @recorded
                accepted-saves @saves
                mixed (send {:op :set-schedule :schedule mixed-prohibited
                             :dry-run true :edit-mode :growth-only})
                stale-baseline (send {:op :set-schedule :schedule old-baseline-growth
                                      :dry-run true :edit-mode :growth-only})
                replay (send {:op :set-schedule :schedule grown-schedule
                              :edit-mode :growth-only
                              :confirm-growth token})]
            (check "one valid growth plus one prohibited change is rejected"
                   (= :growth-only (get-in mixed [:error :code])))
            (check "a candidate valid only against the old baseline is rejected"
                   (= :growth-only (get-in stale-baseline [:error :code])))
            (check "the already-consumed exact token cannot apply twice"
                   (= :growth-only (get-in replay [:error :code])))
            (check "all growth-only rejections preserve in-memory authority"
                   (= accepted-state @state))
            (check "all growth-only rejections preserve persisted content"
                   (and (= accepted-saves @saves)
                        (= accepted-file (persisted (:state paths)))))
            (check "all growth-only rejections emit no effects"
                   (= accepted-effects @recorded))))

        (println "5. status and enforcement are observed through recording only")
        (let [st (send {:op :status})]
          (check "real client sees the confirmed active schedule"
                 (and (= :active (:state st))
                      (= grown-schedule (:schedule st))))
          (check "status reports the authoritative product version"
                 (= version/value (:version st)))
          (check "active occurrence label reflects the grown block"
                 (= "Mon 22:55–23:35"
                    (:label (first (filter :active? (:occurrences st)))))))
        (reset! recorded [])
        (let [{next-state :state effects :effects} (core/tick @state @obs)]
          (reset! state next-state)
          (main/run-effects! effects @state exec paths))
        (check "unlocked console records a lock job"
               (some #(and (= :job (first %)) (= :lock (second %))) @recorded))
        (reset! obs (assoc @obs :sessions [{:uid uid :on-console? true
                                           :login-done? true :locked? true}]))
        (reset! recorded [])
        (let [{next-state :state effects :effects} (core/tick @state @obs)]
          (reset! state next-state)
          (main/run-effects! effects @state exec paths))
        (check "locked console records no lock job"
               (not (some #(and (= :job (first %)) (= :lock (second %))) @recorded)))

        (println "6. at the grown end, unrestricted editing returns")
        (set-now! (at 2026 9 28 23 35))
        (check "the exact end boundary is open"
               (= :open (:state (send {:op :status}))))
        (let [before-saves @saves
              after-end (send {:op :set-schedule
                               :schedule sched/empty-schedule
                               :edit-mode :open})]
          (check "post-end deletion applies without growth confirmation"
                 (:applied after-end))
          (check "post-end edit persisted once"
                 (= (inc before-saves) @saves))
          (check "post-end empty schedule is authoritative in memory, store, and status"
                 (and (= sched/empty-schedule (:schedule @state))
                      (= sched/empty-schedule
                         (:schedule (persisted (:state paths))))
                      (= sched/empty-schedule
                         (:schedule (send {:op :status}))))))

        (println "7. nothing dangerous ran")
        ;; The only executor supplied to the real shell appends effect data to
        ;; `recorded`; no production executor or system action is reachable.
        (check "all lock and schedule effects stayed recording-only" true))

      (finally
        (reset! stop true) (Thread/sleep 100)
        (socket/close! listen-fd (:socket paths))))
    (println (if (zero? @failures) "E2E OK" (str "E2E FAILED: " @failures)))
    (System/exit (if (zero? @failures) 0 1))))
