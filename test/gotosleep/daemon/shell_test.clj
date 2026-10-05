(ns gotosleep.daemon.shell-test
  "In-process tests for store durability and quarantine, non-blocking jobs,
  effect-to-argv mapping, uninstall commands, and control-socket robustness
  against a hostile client. No sudo, no launchd, no locking."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [gotosleep.daemon.store :as store]
            [gotosleep.daemon.jobs :as jobs]
            [gotosleep.daemon.socket :as socket]
            [gotosleep.daemon.main :as main]
            [gotosleep.daemon.core :as core]
            [gotosleep.daemon.log :as daemon-log]
            [gotosleep.clock :as clock]
            [gotosleep.os :as os]
            [gotosleep.tz :as tz]
            [gotosleep.schedule :as sched]
            [gotosleep.version :as version]
            [clojure.edn :as edn]
            [jolt.process :as p]
            [jolt.ffi :as ffi]))

(ffi/load-library)
(ffi/defcfn c-getpid "getpid" [] :int)

(defn tmp [suffix] (str "/tmp/gts-test-" (System/nanoTime) suffix))
(defn open-fd-count [] (count (.list (java.io.File. "/dev/fd"))))

(defn await-job-result [inflight]
  (loop [remaining inflight attempts 0]
    (let [[results remaining'] (jobs/poll remaining (System/nanoTime))]
      (cond
        (seq results) (first results)
        (>= attempts 200) (throw (ex-info "job did not finish" {}))
        :else (do (Thread/sleep 2)
                  (recur remaining' (inc attempts)))))))

(def la (tz/load-zone "America/Los_Angeles"))
(defn la-at [y m d hh mm]
  (* 1000 (tz/local->instant la (+ (* 86400 (tz/days-from-civil y m d))
                                    (* 3600 hh) (* 60 mm)))))

(defn valid-clock [now]
  {:boot-session "TEST-BOOT" :wall-ms now :mono-ns 0 :wall-offset-ms 0
   :last-trusted-ms now :confirmed-mono-ns 0})

(defn daemon-state [now]
  {:schedule sched/empty-schedule :zone "America/Los_Angeles"
   :restore-disablesleep false :clock (valid-clock now) :version version/value})

(defn request-obs [now uid]
  {:mono 0 :awake 0 :wall now :sys-zone "America/Los_Angeles"
   :sessions [{:uid uid :on-console? true :login-done? true :locked? false}]
   :capabilities 15 :sleep-disabled false :jobs []})

(defn response-map [handler-result]
  (let [bytes (if (map? handler-result) (:response-bytes handler-result) handler-result)]
    (edn/read-string (String. bytes "UTF-8"))))

;; --- effect -> argv ---------------------------------------------------------

(deftest effect-argv-mapping
  (let [c jobs/default-config]
    (is (= "com.rubberducking.gotosleep.agent" jobs/agent-label))
    (is (= jobs/agent-label (:agent-label c)))
    (is (= "/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
           (:agent-plist c)))
    (is (= ["/bin/launchctl" "asuser" "503" (:lock-helper c)] (jobs/effect->argv :lock {:uid 503} c)))
    (is (= ["/usr/bin/pmset" "-a" "disablesleep" "0"] (jobs/effect->argv :disablesleep {} c)))
    (is (= ["/usr/bin/pmset" "-a" "disablesleep" "1"] (jobs/effect->argv :restore-sleep {} c)))
    (is (= ["/bin/launchctl" "bootout" "gui/503"] (jobs/effect->argv :logout {:uid 503} c)))
    (is (= ["/usr/bin/sntp" "-t" "5" "time.apple.com"] (jobs/effect->argv :sntp {} c)))
    (is (= ["/bin/launchctl" "print" "gui/503/com.rubberducking.gotosleep.agent"]
           (jobs/effect->argv [:agent-print 503] {:uid 503} c)))
    (is (= ["/bin/launchctl" "kickstart" "gui/503/com.rubberducking.gotosleep.agent"]
           (jobs/effect->argv [:agent-kickstart 503] {:uid 503} c)))
    (is (str/includes? (last (jobs/effect->argv [:agent-bootstrap 503] {:uid 503} c)) "bootstrap gui/503"))
    (is (= 3000 (jobs/timeout-ms :lock)))
    (is (= 10000 (jobs/timeout-ms :sntp)))))

(deftest runtime-job-config-uses-ntp-conf-selection
  (with-redefs [os/ntp-server (fn [] "ntp.internal.example")]
    (is (= "ntp.internal.example"
           (:ntp-server (main/runtime-job-config jobs/default-config))))))

;; --- store -----------------------------------------------------------------

(deftest store-roundtrip-and-fields
  (let [path (tmp ".edn")
        schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
        state {:schedule schedule :zone "UTC"
               :restore-disablesleep true
               :clock (assoc (valid-clock 1)
                             :active-occurrence-key [:mon 20724]
                             :active-since-mono -1000000000
                             :active-since-trusted-ms 0)
               :episode {:should-not "persist"}}]
    (store/save! path state)
    (let [[loaded ev] (store/load! path)]
      (is (= :loaded ev))
      (is (= 1 (:format loaded)))
      (is (= (:schedule state) (:schedule loaded)))
      (is (true? (:restore-disablesleep loaded)))
      (is (= [:mon 20724] (get-in loaded [:clock :active-occurrence-key])))
      (is (= -1000000000 (get-in loaded [:clock :active-since-mono])))
      (is (= 0 (get-in loaded [:clock :active-since-trusted-ms])))
      (is (nil? (:episode loaded))))))            ; runtime fields are not persisted

(deftest store-accepts-legacy-active-anchor-and-rejects-partial-new-tracking
  (let [legacy-path (tmp ".edn")
        legacy {:format 1 :schedule sched/empty-schedule :zone "UTC"
                :restore-disablesleep false
                :clock (assoc (valid-clock 1) :active-since-mono 0)}]
    (spit legacy-path (pr-str legacy))
    (is (= [legacy :loaded] (store/load! legacy-path))))
  (doseq [clock [(assoc (valid-clock 1)
                        :active-occurrence-key [:mon 20724]
                        :active-since-mono 0)
                 (assoc (valid-clock 1)
                        :active-occurrence-key [:noday 20724]
                        :active-since-mono 0
                        :active-since-trusted-ms 1)]]
    (let [path (tmp ".edn")
          state {:format 1 :schedule sched/empty-schedule :zone "UTC"
                 :restore-disablesleep false :clock clock}]
      (spit path (pr-str state))
      (is (= [nil :quarantined] (store/load! path))))))

(deftest store-rejects-invalid-shapes-and-trailing-forms
  (doseq [content [(pr-str {:format 1 :schedule sched/empty-schedule :zone "UTC"
                            :restore-disablesleep false
                            :clock (dissoc (valid-clock 1) :mono-ns)})
                   (str (pr-str {:format 1 :schedule sched/empty-schedule :zone "UTC"
                                 :restore-disablesleep false :clock (valid-clock 1)})
                        " {:trailing true}")
                   (pr-str {:format 1 :schedule (assoc sched/empty-schedule :noday nil)
                            :zone "UTC" :restore-disablesleep false :clock (valid-clock 1)})
                   (pr-str {:format 1 :schedule sched/empty-schedule :zone "Not/AZone"
                            :restore-disablesleep false :clock (valid-clock 1)})]]
    (let [path (tmp ".edn")]
      (spit path content)
      (is (= [nil :quarantined] (store/load! path)) content))))

(deftest store-missing-and-corrupt
  (testing "missing file is a fresh install"
    (is (= [nil :fresh] (store/load! (tmp ".edn")))))
  (testing "a corrupt file is quarantined and the daemon still starts"
    (let [path (tmp ".edn")]
      (spit path "{:format 1 :this is not )( valid")
      (let [[state ev] (store/load! path)]
        (is (= :quarantined ev))
        (is (nil? state))
        (is (seq (filter #(str/includes? % "corrupt")
                         (map str (.listFiles (.getParentFile (java.io.File. path)))))))))))

(deftest failed-corrupt-state-quarantine-starts-fresh
  (let [path (tmp ".edn")]
    (spit path "{:format 1 :broken")
    (with-redefs-fn {#'gotosleep.daemon.store/c-rename
                     (fn [& _] [-1 13])}
      #(let [[persisted event] (store/load! path)
             initialized (:state (core/init persisted "BOOT" (request-obs 1 503)))]
         (is (= :quarantine-failed event))
         (is (nil? persisted))
         (is (= sched/empty-schedule (:schedule initialized)))))))

(deftest store-readonly-dir-throws
  ;; On a read-only dir, save! throws (the caller reports :storage and leaves
  ;; state unchanged); nothing partial is left readable.
  (let [dir (tmp "-ro")]
    (.mkdirs (java.io.File. dir))
    (.setWritable (java.io.File. dir) false)
    (is (thrown? Throwable (store/save! (str dir "/state.edn") {:schedule {} :zone "UTC" :clock {}})))
    (.setWritable (java.io.File. dir) true)))

;; --- a hung job does not delay the tick ------------------------------------

(deftest completed-jobs-release-all-process-pipes
  (let [before (open-fd-count)
        results
        (with-redefs [jobs/effect->argv (fn [& _] ["/usr/bin/true"])]
          (mapv (fn [_]
                  (await-job-result
                   (jobs/spawn {} :lock {} jobs/default-config (System/nanoTime))))
                (range 60)))]
    (is (every? #(zero? (:exit %)) results))
    (is (<= (open-fd-count) (+ before 2))
        "completed jobs must not retain stdin/stdout/stderr descriptors")))

(deftest timed-out-and-failed-polls-release-process-pipes
  (testing "timeout"
    (let [before (open-fd-count)
          started (System/nanoTime)
          inflight
          (with-redefs [jobs/effect->argv (fn [& _] ["/bin/sleep" "10"])]
            (jobs/spawn {} :lock {} jobs/default-config started))
          [results remaining] (jobs/poll inflight (+ started (* 4000 1000000)))]
      (is (:timed-out (first results)))
      (is (empty? remaining))
      (is (<= (open-fd-count) (+ before 2)))))
  (testing "poll failure"
    (let [before (open-fd-count)
          inflight
          (with-redefs [jobs/effect->argv (fn [& _] ["/bin/sleep" "10"])]
            (jobs/spawn {} :lock {} jobs/default-config (System/nanoTime)))
          [results remaining]
          (with-redefs [p/alive? (fn [_] (throw (ex-info "poll failed" {})))]
            (jobs/poll inflight (System/nanoTime)))]
      (is (str/includes? (:err (first results)) "poll failed"))
      (is (empty? remaining))
      (is (<= (open-fd-count) (+ before 2))))))

(deftest hung-job-does-not-block
  (let [inflight (atom {})
        start (System/nanoTime)]
    ;; spawn a 10 s sleep as the :lock job, then poll: must return at once
    (swap! inflight assoc :lock {:proc (p/process ["/bin/sleep" "10"] {:out :string})
                                 :argv ["sleep"] :started-ns start})
    (let [t0 (System/currentTimeMillis)
          [results remaining] (jobs/poll @inflight (+ start (* 100 1000000)))]
      (is (< (- (System/currentTimeMillis) t0) 500) "poll returns without waiting for the job")
      (is (empty? results))
      (is (contains? remaining :lock)))
    (testing "past its timeout the job is destroyed and reported"
      (let [[results _] (jobs/poll @inflight (+ start (* 4000 1000000)))]
        (is (= [:lock] (map :key results)))
        (is (:timed-out (first results)))))))

(deftest job-timeouts-ignore-wall-clock-direction
  (let [start 900000000000
        proc (p/process ["/bin/sleep" "10"] {:out :string})
        entry {:lock {:proc proc :argv ["sleep"] :started-ns start}}]
    (try
      (is (contains? (second (jobs/poll entry (- start (* 60 1000000000)))) :lock))
      (is (:timed-out (first (first (jobs/poll entry (+ start (* 4 1000000000)))))))
      (finally (try (p/destroy proc) (catch Throwable _ nil))))))

(deftest hung-job-does-not-delay-a-new-lock-effect
  (let [started (System/nanoTime)
        proc (p/process ["/bin/sleep" "10"] {:out :string})
        inflight (atom {:other {:proc proc :argv ["sleep"] :started-ns started}})
        state (atom (daemon-state (la-at 2026 9 29 12 0)))
        obs-atom (atom {})
        effects (atom [])
        observation (request-obs (la-at 2026 9 29 12 0) 503)]
    (try
      (with-redefs [main/observe (fn [_] observation)
                    core/tick (fn [s _] {:state s :effects [[:job :lock {:uid 503}]]})]
        (let [t0 (System/nanoTime)]
          (main/tick-once! state inflight nil nil #(swap! effects conj %) {:log (tmp ".log")} obs-atom)
          (is (< (quot (- (System/nanoTime) t0) 1000000) 500))))
      (is (= [[:job :lock {:uid 503}]] @effects))
      (is (contains? @inflight :other))
      (finally (try (p/destroy proc) (catch Throwable _ nil))))))

;; --- shutdown persists -----------------------------------------------------

(deftest shutdown-persists
  (let [path (tmp ".edn")
        state (atom {:schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
                     :zone "UTC" :restore-disablesleep false :clock (valid-clock 42)})]
    (with-redefs [os/wall-ms (fn [] 10042) os/mono-ns (fn [] 0)]
      (is (true? (main/shutdown! state {:state path :log (tmp ".log")}))))
    (is (= 10000 (get-in @state [:clock :wall-offset-ms])))
    (is (= 42 (get-in (first (store/load! path)) [:clock :last-trusted-ms])))))

(deftest durable-sleep-is-gated-by-persist-success
  (let [effects [[:persist] [:sleep-system {:requires-persist true}]]
        ran (atom [])]
    (with-redefs [store/save! (fn [_ _] (throw (ex-info "disk full" {})))
                  daemon-log/append! (fn [& _] nil)]
      (is (= #{:sleep-system}
             (:deferred (main/run-effects! effects {} #(swap! ran conj %)
                                            {:state "unused" :log "unused"})))))
    (is (empty? @ran))
    (with-redefs [store/save! (fn [_ _] :ok)
                  daemon-log/append! (fn [& _] nil)]
      (main/run-effects! effects {} #(swap! ran conj %) {:state "unused" :log "unused"}))
    (is (= [[:sleep-system {:requires-persist true}]] @ran))))

(deftest deferred-required-sleep-does-not-advance-or-log-an-attempt
  (let [now (la-at 2026 9 29 12 0)
        previous (assoc (daemon-state now)
                        :restore-disablesleep true :last-persist-awake 0
                        :episode {:uid 503 :elapsed 15000 :last-awake 0
                                  :last-sleep nil :first-sleep nil
                                  :slept? false :last-logout {}})
        advanced (-> previous
                     (assoc :last-persist-awake 16000000000)
                     (assoc-in [:episode :elapsed] 16000)
                     (assoc-in [:episode :last-sleep] 16000)
                     (assoc-in [:episode :first-sleep] 16000))
        state (atom previous)
        logs (atom [])
        observation (request-obs now 503)
        exec (main/make-executor (atom {}) jobs/default-config {:log "unused"})]
    (with-redefs [main/observe (fn [_] observation)
                  core/tick (fn [_ _]
                              {:state advanced
                               :effects [[:persist]
                                         [:sleep-system {:requires-persist true}]]})
                  store/save! (fn [& _] (throw (ex-info "disk full" {})))
                  daemon-log/append! (fn [_ level event data]
                                       (swap! logs conj [level event data]))
                  gotosleep.daemon.power/sleep-system!
                  (fn [] (throw (ex-info "must not execute" {})))]
      (main/tick-once! state (atom {}) nil nil exec
                       {:state "unused" :log "unused"} (atom {})))
    (is (nil? (get-in @state [:episode :first-sleep])))
    (is (nil? (get-in @state [:episode :last-sleep])))
    (is (:restore-disablesleep @state) "durable restore intent remains retryable")
    (is (nil? (:last-persist-awake @state)))
    (is (not-any? #(= :sleep-requested (second %)) @logs))))

(deftest deferred-disablesleep-job-clears-only-inflight-runtime-state
  (let [state {:restore-disablesleep true :disablesleep-pending true :sleep-ready false}
        result (atom nil)]
    (with-redefs [store/save! (fn [& _] (throw (ex-info "disk full" {})))
                  daemon-log/append! (fn [& _] nil)]
      (reset! result
              (main/run-effects! [[:persist]
                                  [:job :disablesleep {:requires-persist true}]]
                                 state (fn [_] (throw (ex-info "must not execute" {})))
                                 {:state "unused" :log "unused"})))
    (is (= #{:disablesleep} (:deferred @result)))
    (is (= {:restore-disablesleep true
            :disablesleep-pending false :sleep-ready false}
           (core/reconcile-deferred-effects {} state (:deferred @result))))))

(deftest persistence-lifecycle-makes-shutdown-the-final-write
  (let [lifecycle (main/persistence-lifecycle)
        state (atom (daemon-state 100))
        ordinary-entered (promise)
        release-ordinary (promise)
        shutdown-attempted (promise)
        writes (atom [])
        paths {:state "unused" :log "unused"}]
    (with-redefs [store/save! (fn [_ value]
                                (when (zero? (count @writes))
                                  (deliver ordinary-entered true)
                                  (deref release-ordinary 2000 :timeout))
                                (swap! writes conj (get-in value [:clock :wall-offset-ms]))
                                :ok)
                  os/wall-ms (fn [] 200)
                  os/mono-ns (fn [] 0)
                  daemon-log/append! (fn [& _] nil)]
      (let [ordinary (future (main/run-effects! [[:persist]] @state (fn [_] nil)
                                                paths lifecycle))]
        (is (= true (deref ordinary-entered 2000 :timeout)))
        (let [shutdown (future
                         (deliver shutdown-attempted true)
                         (main/shutdown! state paths lifecycle))]
          (is (= true (deref shutdown-attempted 2000 :timeout)))
          (deliver release-ordinary true)
          (is (map? (deref ordinary 2000 :timeout)))
          (is (true? (deref shutdown 2000 false)))))
      (is (= [0 100] @writes))
      (is (= :shutdown (main/persistence-phase lifecycle)))
      (main/run-effects! [[:persist]] @state (fn [_] nil) paths lifecycle)
      (is (= [0 100] @writes) "ordinary persistence cannot follow shutdown")
      (let [handler (main/make-handler state (atom (request-obs 200 0))
                                       (fn [_] nil) paths identity lifecycle)
            schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
            req (.getBytes (pr-str {:op :set-schedule :schedule schedule}) "UTF-8")]
        (is (= :storage (get-in (response-map (handler req 0)) [:error :code])))
        (is (= [0 100] @writes) "request persistence cannot follow shutdown")))))

(deftest uninstall-marker-disables-persistence-even-if-bootout-fails
  (let [now (la-at 2026 9 29 12 0)
        lifecycle (main/persistence-lifecycle)
        state (atom (daemon-state now))
        paths {:state "removed-support/state.edn" :log "removed-logs/daemon.log"}
        events (atom [])
        saves (atom 0)
        handler (main/make-handler state (atom (request-obs now 0))
                                   #(swap! events conj [:effect %]) paths identity lifecycle)
        req (.getBytes (pr-str {:op :uninstall}) "UTF-8")]
    (with-redefs [main/uninstall! (fn [_]
                                    (swap! events conj [:uninstall (main/persistence-phase lifecycle)])
                                    [{:id :daemon-bootout :result {:ok false :exit 1}}])
                  store/save! (fn [& _] (swap! saves inc))
                  daemon-log/append! (fn [& args] (swap! events conj [:log args]))]
      (let [result (handler req 0)]
        ((:after-write result)))
      (is (= :uninstalling (main/persistence-phase lifecycle)))
      (is (some #(= [:uninstall :uninstalling] %) @events))
      (reset! events [])
      (is (true? (main/shutdown! state paths lifecycle)))
      (main/run-effects! [[:persist]] @state (fn [_] nil) paths lifecycle)
      (with-redefs [main/observe (fn [_]
                                  (swap! events conj :observed)
                                  (request-obs now 0))]
        (main/tick-once! state (atom {}) nil nil (fn [_] (swap! events conj :effect))
                         paths (atom {}) lifecycle))
      (is (zero? @saves))
      (is (empty? @events)
          "shutdown and a surviving loop neither persist nor recreate normal paths"))))

(deftest tick-survives-shell-failures-and-retries-persistence-promptly
  (let [state (atom (assoc (daemon-state (la-at 2026 9 29 12 0)) :last-persist-awake 123))
        inflight (atom {})
        obs-atom (atom {})
        observation (request-obs (la-at 2026 9 29 12 0) 503)]
    (with-redefs [main/observe (fn [_] observation)
                  core/tick (fn [s _] {:state s :effects [[:persist] [:sleep-system]]})
                  store/save! (fn [& _] (throw (ex-info "disk full" {})))
                  daemon-log/append! (fn [& _] (throw (ex-info "logger failed" {})))]
      (is (nil? (main/tick-once! state inflight nil nil
                                  (fn [_] (throw (ex-info "executor failed" {})))
                                  {:state (tmp ".edn") :log (tmp ".log")} obs-atom))))
    (is (nil? (:last-persist-awake @state)))
    (with-redefs [jobs/poll (fn [& _] (throw (ex-info "poll failed" {})))
                  main/observe (fn [_] (throw (ex-info "observe failed" {})))
                  daemon-log/append! (fn [& _] nil)]
      (is (nil? (main/tick-once! state inflight nil nil (fn [_] nil)
                                  {:state (tmp ".edn") :log (tmp ".log")} obs-atom))))))

(deftest power-failure-does-not-escape-effect-loop
  (let [exec (main/make-executor (atom {}) jobs/default-config {:log (tmp ".log")})]
    (with-redefs [gotosleep.daemon.power/sleep-system! (fn [] (throw (ex-info "power failed" {})))
                  daemon-log/append! (fn [& _] nil)]
      (is (= {:persist-failed? false :deferred #{}}
             (main/run-effects! [[:sleep-system]] {} exec {:log (tmp ".log")}))))))

(deftest handler-persists-before-state-effects-and-response
  (let [now (la-at 2026 9 29 12 0)
        original (daemon-state now)
        state (atom original)
        obs (atom (request-obs now 503))
        order (atom [])
        paths {:state (tmp ".edn") :log (tmp ".log")}
        handler (main/make-handler state obs #(swap! order conj [:effect %]) paths identity)
        schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
        req (.getBytes (pr-str {:op :set-schedule :schedule schedule}) "UTF-8")]
    (with-redefs [store/save! (fn [_ _]
                                (swap! order conj :save)
                                (throw (ex-info "disk full" {})))]
      (is (= :storage (get-in (response-map (handler req 503)) [:error :code])))
      (is (= original @state))
      (is (= [:save] @order)))
    (reset! order [])
    (with-redefs [store/save! (fn [_ _] (swap! order conj :save) :ok)]
      (is (true? (:ok (response-map (handler req 503)))))
      (is (= schedule (:schedule @state)))
      (is (= :save (first @order)))
      (is (= :effect (first (second @order)))))))

(deftest mutating-requests-refresh-identity-and-freeze-boundary
  (testing "fresh console identity has authorization precedence"
    (let [open-now (la-at 2026 9 29 12 0)
          schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
          req (.getBytes (pr-str {:op :set-schedule :schedule schedule}) "UTF-8")
          state (atom (daemon-state open-now))
          stale (atom (request-obs open-now 503))
          handler (main/make-handler
                   state stale (fn [_] nil) {:state (tmp ".edn") :log (tmp ".log")}
                   #(assoc % :sessions (:sessions (request-obs open-now 504))))]
      (is (= :forbidden (get-in (response-map (handler req 503)) [:error :code])))))
  (testing "cached open, fresh growth-only rejects an explicitly stale mode without persistence"
    (let [freeze-start (la-at 2026 9 28 15 0)
          old-schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
          grown-schedule (assoc old-schedule :mon {:start "22:00" :end "08:00"})
          state (atom (assoc (daemon-state (dec freeze-start)) :schedule old-schedule))
          stale (atom (assoc (request-obs (dec freeze-start) 503) :mono 0))
          observations (atom 0)
          saves (atom 0)
          effects (atom [])
          fresh (fn [cached]
                  (swap! observations inc)
                  (assoc cached :mono 1000000 :wall freeze-start))
          handler (main/make-handler
                   state stale #(swap! effects conj %) {:state (tmp ".edn") :log (tmp ".log")} fresh)
          req (.getBytes (pr-str {:op :set-schedule
                                  :schedule grown-schedule
                                  :dry-run true
                                  :edit-mode :open})
                         "UTF-8")]
      (with-redefs [store/save! (fn [& _] (swap! saves inc))]
        (let [response (response-map (handler req 0))]
          (is (= :stale-edit-mode (get-in response [:error :code])))
          (is (= :growth-only (get-in response [:error :expected])))
          (is (= :open (get-in response [:error :actual])))))
      (is (= 1 @observations))
      (is (zero? @saves))
      (is (empty? @effects))
      (is (= old-schedule (:schedule @state)))))
  (testing "cached growth-only, fresh open rejects stale mode, then an omitted mode persists once"
    (let [end (la-at 2026 9 29 7 0)
          old-schedule (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
          state (atom (assoc (daemon-state (dec end)) :schedule old-schedule))
          stale (atom (assoc (request-obs (dec end) 503) :mono 0))
          observations (atom 0)
          saves (atom 0)
          effects (atom [])
          fresh (fn [cached]
                  (swap! observations inc)
                  (assoc cached :mono 1000000 :wall end))
          handler (main/make-handler
                   state stale #(swap! effects conj %) {:state (tmp ".edn") :log (tmp ".log")} fresh)
          stale-req (.getBytes (pr-str {:op :set-schedule
                                        :schedule sched/empty-schedule
                                        :edit-mode :growth-only})
                               "UTF-8")
          direct-req (.getBytes (pr-str {:op :set-schedule
                                         :schedule sched/empty-schedule})
                                "UTF-8")]
      (with-redefs [store/save! (fn [& _] (swap! saves inc) :ok)]
        (let [stale-response (response-map (handler stale-req 0))]
          (is (= :stale-edit-mode (get-in stale-response [:error :code])))
          (is (= :open (get-in stale-response [:error :expected])))
          (is (= :growth-only (get-in stale-response [:error :actual]))))
        (is (true? (:ok (response-map (handler direct-req 0))))))
      (is (= 2 @observations))
      (is (= 1 @saves))
      (is (= sched/empty-schedule (:schedule @state)))
      (is (= [:schedule-set] (mapv #(nth % 2) @effects))))))

(deftest uninstall-continues-and-records-every-failure
  (let [commands (atom [])
        logs (atom [])
        runner (fn [argv _]
                 (let [n (count (swap! commands conj argv))]
                   (case n
                     1 {:exit 7 :out "" :err "denied"}
                     2 {:exit -1 :out "" :err "timeout" :timed-out true}
                     {:exit 0 :out "" :err ""})))
        paths {:log (tmp ".log") :uninstall-log (tmp "-uninstall.log")
               :socket (tmp ".sock") :support (tmp "-support") :app (tmp ".app")
               :logs (tmp "-logs") :agent-plist (tmp "-agent.plist")
               :daemon-plist (tmp "-daemon.plist")}]
    (with-redefs [os/sessions (fn [] :read-failed)
                  daemon-log/append! (fn [path level event data]
                                       (swap! logs conj [path level event data]))]
      (let [results (main/uninstall! paths runner)]
        (is (= 8 (count results)))
        (is (= 8 (count @commands)))
        (is (= [:remove-agent-plist :remove-app :remove-logs :remove-socket
                :remove-support :forget-package :remove-daemon-plist :daemon-bootout]
               (mapv :id results)))
        (is (= 3 (count (filter #(= :uninstall-step-failed (nth % 2)) @logs))))
        (is (every? #(= (:uninstall-log paths) (first %))
                    (filter #(= :uninstall-step-failed (nth % 2)) @logs)))))))

(deftest uninstall-defaults-use-public-identities
  (let [commands (atom [])
        runner (fn [argv _]
                 (swap! commands conj argv)
                 {:ok true :exit 0 :out "" :err ""})
        paths {:log (tmp ".log")
               :uninstall-log (tmp "-uninstall.log")
               :socket (tmp ".sock")
               :support (tmp "-support")
               :app (tmp ".app")
               :logs (tmp "-logs")}]
    (with-redefs [os/sessions (fn [] [{:uid 503}])
                  daemon-log/append! (fn [& _] nil)]
      (main/uninstall! paths runner))
    (is (= "com.rubberducking.gotosleep.daemon" main/daemon-label))
    (is (= "com.rubberducking.gotosleep.pkg" main/package-receipt))
    (is (some #{["/bin/launchctl" "bootout"
                 "gui/503/com.rubberducking.gotosleep.agent"]}
              @commands))
    (is (some #{["/bin/rm" "-rf"
                 "/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"]}
              @commands))
    (is (some #{["/usr/sbin/pkgutil" "--forget"
                 "com.rubberducking.gotosleep.pkg"]}
              @commands))
    (is (some #{["/bin/rm" "-f"
                 "/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist"]}
              @commands))
    (is (some #{["/bin/launchctl" "bootout"
                 "system/com.rubberducking.gotosleep.daemon"]}
              @commands))))

(deftest real-persist-reload-clean-new-boot-discounts-forward-jump
  (let [path (tmp ".edn")
        base 1790000000000
        jump (* 10 60 60 1000)
        initial-obs {:mono 0 :awake 0 :wall base :sys-zone "UTC" :sessions []
                     :capabilities 15 :sleep-disabled false :jobs []}
        state0 (:state (core/init nil "BOOT-A" initial-obs))
        elapsed1 60000
        obs1 (assoc initial-obs :mono (* elapsed1 1000000) :awake (* elapsed1 1000000)
                    :wall (+ base elapsed1 jump))
        tick1 (core/tick state0 obs1)
        paths {:state path :log (tmp ".log")}
        _ (main/run-effects! (:effects tick1) (:state tick1) (fn [_] nil) paths)
        [periodic _] (store/load! path)
        elapsed2 120000
        clean-obs {:mono (* elapsed2 1000000) :wall (+ base elapsed2 jump)}
        _ (main/persist-state! path (:state tick1) clean-obs)
        [clean _] (store/load! path)
        boot-obs (assoc initial-obs :mono 0 :awake 0 :wall (+ base elapsed2 jump))
        restarted (:state (core/init clean "BOOT-B" boot-obs))]
    (is (= jump (get-in periodic [:clock :wall-offset-ms])))
    (is (= (+ base elapsed2) (get-in clean [:clock :last-trusted-ms])))
    (is (= (+ base elapsed2) (core/now-ms restarted boot-obs)))
    (is (clock/suspect? (:clock restarted) (:wall boot-obs) (:mono boot-obs)))))

;; --- socket robustness -----------------------------------------------------

(deftest socket-server-uses-separate-request-and-response-deadlines
  (let [request-deadline (atom nil)
        response-deadline (atom nil)
        after-write-called (atom false)
        closed (atom 0)]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/poll-ready? (fn [& _] true)
       #'gotosleep.daemon.socket/c-accept (fn [& _] [42 0])
       #'gotosleep.daemon.socket/set-nosigpipe (fn [_] nil)
       #'gotosleep.daemon.socket/set-nonblocking! (fn [_] nil)
       #'gotosleep.daemon.socket/read-line-bytes
       (fn [_ deadline]
         (reset! request-deadline deadline)
         (.getBytes "{:op :status}" "UTF-8"))
       #'gotosleep.daemon.socket/peer-uid (fn [_] 503)
       #'gotosleep.daemon.socket/write-all!
       (fn [_ _ deadline]
         (reset! response-deadline deadline)
         true)
       #'gotosleep.daemon.socket/c-close
       (fn [_] (swap! closed inc) 0)}
      #(do
         (is (true?
              (socket/serve-one!
               7
               (fn [_ uid]
                 (is (= 503 uid))
                 {:response-bytes (.getBytes "{:ok true}\n" "UTF-8")
                  :after-write (fn [] (reset! after-write-called true))})
               0)))
         (is (= (* 500 1000000)
                (deref #'gotosleep.daemon.socket/request-budget-ns)))
         (is (= (* 2000 1000000)
                (deref #'gotosleep.daemon.socket/response-budget-ns)))
         (is (> @response-deadline @request-deadline))))
    (is @after-write-called)
    (is (= 1 @closed))))

(deftest socket-client-uses-separate-request-and-response-deadlines
  (let [request-deadline (atom nil)
        response-deadline (atom nil)
        order (atom [])
        closed (atom 0)]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/c-socket (fn [& _] [42 0])
       #'gotosleep.daemon.socket/c-connect
       (fn [& _]
         (swap! order conj :connect)
         [-1 36])
       #'gotosleep.daemon.socket/set-nonblocking!
       (fn [_] (swap! order conj :nonblocking))
       #'gotosleep.daemon.socket/poll-events
       (fn [& _]
         (swap! order conj :connect-poll)
         4)
       #'gotosleep.daemon.socket/socket-error
       (fn [_]
         (swap! order conj :socket-error)
         0)
       #'gotosleep.daemon.socket/write-all!
       (fn [_ _ deadline]
         (swap! order conj :write)
         (reset! request-deadline deadline)
         true)
       #'gotosleep.daemon.socket/read-line-bytes
       (fn [_ deadline]
         (swap! order conj :read)
         (reset! response-deadline deadline)
         (.getBytes "{:ok true}" "UTF-8"))
       #'gotosleep.daemon.socket/c-close
       (fn [_]
         (swap! order conj :close)
         (swap! closed inc)
         0)}
      #(is (= "{:ok true}" (socket/request! "/tmp/gts-deadline-test.sock" "{:op :status}"))))
    (is (= (* 500 1000000)
           (deref #'gotosleep.daemon.socket/request-budget-ns)))
    (is (= (* 2000 1000000)
           (deref #'gotosleep.daemon.socket/response-budget-ns)))
    (is (> @response-deadline @request-deadline))
    (is (= [:nonblocking :connect :connect-poll :socket-error :write :read :close] @order))
    (is (= 1 @closed))))

(deftest socket-write-stops-on-fatal-error-and-retries-partials
  (let [calls (atom 0)]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/poll-events (fn [& _] 4)
       #'gotosleep.daemon.socket/c-write
       (fn [& _]
         (swap! calls inc)
         [-1 32])}
      #(is (false?
            (#'gotosleep.daemon.socket/write-all!
             42 (.getBytes "abc" "UTF-8") (+ (System/nanoTime) (* 2 1000000000))))))
    (is (= 1 @calls)))
  (let [requested (atom [])]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/poll-events (fn [& _] 4)
       #'gotosleep.daemon.socket/c-write
       (fn [_ ptr n]
         (swap! requested conj
                [n (String. (byte-array
                              (map #(unchecked-byte (ffi/read ptr :uint8 %))
                                   (range n)))
                            "UTF-8")])
         [(if (= 1 (count @requested)) 2 n) 0])}
      #(is (true?
            (#'gotosleep.daemon.socket/write-all!
             42 (.getBytes "abcdef" "UTF-8") (+ (System/nanoTime) 1000000000)))))
    (is (= [[6 "abcdef"] [4 "cdef"]] @requested)))
  (let [writes (atom 0)]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/poll-events (fn [& _] nil)
       #'gotosleep.daemon.socket/c-write
       (fn [& _] (swap! writes inc) [1 0])}
      #(is (false?
            (#'gotosleep.daemon.socket/write-all!
             42 (.getBytes "abc" "UTF-8") (+ (System/nanoTime) 1000000000)))))
    (is (zero? @writes)))
  (with-redefs-fn
    {#'gotosleep.daemon.socket/c-poll (fn [& _] [-1 9])}
    #(is (nil? (#'gotosleep.daemon.socket/poll-events 42 4 0)))))

(deftest socket-read-stops-on-fatal-error
  (let [calls (atom 0)]
    (with-redefs-fn
      {#'gotosleep.daemon.socket/poll-events (fn [& _] 1)
       #'gotosleep.daemon.socket/c-read
       (fn [& _]
         (swap! calls inc)
         [-1 54])}
      #(is (nil?
            (#'gotosleep.daemon.socket/read-line-bytes
             42 (+ (System/nanoTime) 1000000000)))))
    (is (= 1 @calls))))

(defn with-server
  "Bind a temp socket, run `f` with its path, and clean up. The handler echoes
  the peer uid and byte count, or {:bad true} for a nil/over-cap request."
  [handler f]
  (let [path (tmp ".sock")
        fd (socket/bind-listen! path)
        stop (atom false)
        server (future (while (not @stop) (socket/serve-one! fd handler 50)))]
    (try (f path)
         (finally (reset! stop true) (Thread/sleep 100) (socket/close! fd path)))))

(deftest socket-serves-and-reports-peer
  (with-server
    (fn [bytes uid] (.getBytes (str "{:uid " uid " :n " (alength bytes) "}\n") "UTF-8"))
    (fn [path]
      (let [resp (socket/request! path "{:op :status}")]
        (is (str/includes? resp (str ":uid " (str/trim (:out (p/sh "id" "-u")))))
            (str "resp=" resp))
        (is (str/includes? resp ":n 13"))))))

(deftest real-socket-enforces-and-persists-growth-only-schedule-requests
  (let [now (la-at 2026 9 28 20 0)
        uid (parse-long (str/trim (:out (p/sh "id" "-u"))))
        schedule (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"}
                        :thu {:start "23:00" :end "07:00"})
        grown (assoc schedule :mon {:start "22:00" :end "08:00"})
        mixed (assoc grown :thu {:start "23:30" :end "07:00"})
        window-invalid (assoc schedule :tue {:start "12:00" :end "13:00"})
        state (atom (assoc (daemon-state now) :schedule schedule))
        observation (atom (request-obs now uid))
        saves (atom 0)
        effects (atom [])
        paths {:state (tmp ".edn") :log (tmp ".log")}
        handler (main/make-handler state observation #(swap! effects conj %) paths identity)
        original-save! store/save!
        rejected [[{:op :set-schedule :schedule schedule
                    :dry-run true :edit-mode :growth-only}
                   :growth-only]
                  [{:op :set-schedule :schedule sched/empty-schedule}
                   :growth-only]
                  [{:op :set-schedule :schedule mixed :dry-run true}
                   :growth-only]
                  [{:op :set-schedule :schedule {:invalid true} :dry-run true}
                   :invalid-schedule]
                  [{:op :set-schedule :schedule window-invalid :dry-run true}
                   :window]
                  [{:op :set-schedule :schedule grown
                    :dry-run true :edit-mode :open}
                   :stale-edit-mode]
                  [{:op :set-schedule :schedule grown :confirm-freeze []}
                   :stale-confirmation]
                  [{:op :set-schedule :schedule grown :confirm-growth {}}
                   :bad-request]]]
    (with-redefs [store/save! (fn [path value]
                                (swap! saves inc)
                                (original-save! path value))]
      (with-server
        handler
        (fn [path]
          (doseq [[request expected] rejected]
            (let [response (edn/read-string (socket/request! path (pr-str request)))
                  error (:error response)]
              (is (= expected (:code error)) (pr-str request))))
          (is (zero? @saves))
          (is (empty? @effects))
          (is (= schedule (:schedule @state)))
          (is (not (.exists (java.io.File. (:state paths)))))
          (let [dry-request {:op :set-schedule
                             :schedule grown
                             :dry-run true
                             :edit-mode :growth-only}
                dry (edn/read-string (socket/request! path (pr-str dry-request)))
                token (:confirm-growth dry)]
            (is (:ok dry))
            (is (false? (:applied dry)))
            (is (= :growth-only (:edit-mode dry)))
            (is (= schedule (:baseline token)))
            (is (= grown (:candidate token)))
            (is (= (la-at 2026 9 29 7 0) (:authority-unfrozen-at token)))
            (is (= (la-at 2026 9 29 8 0) (:confirm-until-at token)))
            (is (zero? @saves))
            (doseq [request [{:op :set-schedule
                              :schedule grown
                              :edit-mode :growth-only}
                             {:op :set-schedule
                              :schedule grown
                              :edit-mode :growth-only
                              :confirm-growth (assoc token :zone "UTC")}]]
              (let [response (edn/read-string (socket/request! path (pr-str request)))]
                (is (= :confirm-required (get-in response [:error :code])))
                (is (= token (get-in response [:error :confirm-growth])))))
            (is (zero? @saves))
            (let [apply-request {:op :set-schedule
                                 :schedule grown
                                 :edit-mode :growth-only
                                 :confirm-growth token}
                  applied (edn/read-string (socket/request! path (pr-str apply-request)))]
              (is (:ok applied))
              (is (:applied applied))
              (is (= :growth-only (:edit-mode applied)))
              (is (= grown (get-in applied [:status :schedule])))
              (is (= :frozen (get-in applied [:status :state])))
              (is (= grown (:schedule @state)))
              (is (= grown
                     (:schedule (edn/read-string (slurp (:state paths))))))
              (is (= 1 @saves))
              (is (= [:schedule-set] (mapv #(nth % 2) @effects))))
            (let [duplicate (edn/read-string
                             (socket/request! path
                                              (pr-str {:op :set-schedule
                                                       :schedule grown
                                                       :edit-mode :growth-only
                                                       :confirm-growth token})))]
              (is (= :growth-only (get-in duplicate [:error :code])))
              (is (= 1 @saves))
              (is (= [:schedule-set] (mapv #(nth % 2) @effects))))))))))

(deftest socket-slow-handler-gets-fresh-response-deadline
  (with-server
    (fn [_ _]
      (Thread/sleep 1200)
      (.getBytes "{:ok true}\n" "UTF-8"))
    (fn [path]
      (let [started (System/nanoTime)
            resp (socket/request! path "{:op :status}")
            elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
        (is (= "{:ok true}" resp))
        (is (>= elapsed-ms 1000))))))

(deftest socket-early-close-does-not-crash
  (with-server
    (fn [_ _] (.getBytes "{:ok true}\n" "UTF-8"))
    (fn [path]
      ;; a client that connects, sends, and closes before reading
      (dotimes [_ 20]
        (try (p/sh "sh" "-c" (str "printf '{:op :status}\\n' | nc -U -w 0 " path)) (catch Throwable _ nil)))
      ;; the server is still alive: a normal request still works
      (Thread/sleep 200)
      (is (str/includes? (or (socket/request! path "{:op :status}") "") ":ok true")))))

(deftest socket-oversize-is-read-and-handler-decides
  (with-server
    (fn [bytes _] (.getBytes (str "{:n " (alength bytes) "}\n") "UTF-8"))
    (fn [path]
      ;; send > 64 KiB on one line; server caps the read near the limit
      (let [big (str "{:op :status :pad \"" (apply str (repeat 70000 "x")) "\"}")
            resp (socket/request! path big)]
        (is (some? resp))))))

(deftest socket-requires-newline-and-handles-large-responses
  (let [called (atom 0)]
    (with-server
      (fn [_ _]
        (swap! called inc)
        (.getBytes "{:ok true}\n" "UTF-8"))
      (fn [path]
        (p/sh "sh" "-c" (str "printf %s '{:op :status}' | /usr/bin/nc -U -w 1 " path))
        (is (zero? @called))
        (is (str/includes? (or (socket/request! path "{:op :status}") "") ":ok true"))
        (is (= 1 @called)))))
  (let [payload (apply str (repeat 60000 "x"))]
    (with-server
      (fn [_ _] (.getBytes (str payload "\n") "UTF-8"))
      (fn [path]
        (is (= payload (socket/request! path "{:op :status}")))))))

(deftest socket-rejects-overlong-unix-path
  (is (thrown? Throwable (socket/bind-listen! (str "/tmp/" (apply str (repeat 104 "x")))))))

(deftest uninstall-starts-only-after-response-write
  (let [now (la-at 2026 9 29 12 0)
        uid (parse-long (str/trim (:out (p/sh "id" "-u"))))
        state (atom (daemon-state now))
        obs (atom (request-obs now uid))
        started (promise)
        paths {:state (tmp ".edn") :log (tmp ".log") :uninstall-log (tmp "-uninstall.log")}]
    (with-redefs [main/uninstall! (fn [_] (deliver started :started))]
      (with-server
        (main/make-handler state obs (fn [_] nil) paths identity)
        (fn [path]
          (is (= {:ok true} (edn/read-string (socket/request! path "{:op :uninstall}"))))
          (is (= :started (deref started 1000 :timeout))))))))

(deftest fifty-silent-clients-do-not-delay-lock-ticks-or-change-pid
  (let [path (tmp ".sock")
        fd (socket/bind-listen! path)
        clients (atom [])
        durations (atom [])
        locks (atom 0)
        state (atom (daemon-state (la-at 2026 9 29 12 0)))
        inflight (atom {})
        obs-atom (atom {})
        obs (request-obs (la-at 2026 9 29 12 0) 503)
        pid (c-getpid)
        connect-silent!
        (fn []
          (let [[client-fd e] (socket/c-socket 1 1 0)]
            (when (neg? client-fd)
              (throw (ex-info "test client socket failed" {:errno e})))
            (try
              (ffi/with-alloc [addr 106]
                (#'gotosleep.daemon.socket/write-sockaddr!
                 addr (.getBytes ^String path "UTF-8"))
                (let [[r e] (socket/c-connect client-fd addr 106)]
                  (when (neg? r)
                    (throw (ex-info "test client connect failed" {:errno e})))))
              (swap! clients conj client-fd)
              client-fd
              (catch Throwable ex
                (socket/c-close client-fd)
                (throw ex)))))]
    (try
      (with-redefs [main/observe (fn [_] obs)
                    core/tick (fn [s _] {:state s :effects [[:job :lock {:uid 503}]]})]
        (dotimes [_ 50]
          (connect-silent!)
          (let [started (System/nanoTime)]
            (main/tick-once! state inflight fd
                             (fn [_ _] (.getBytes "{:ok true}\n" "UTF-8"))
                             (fn [effect]
                               (when (and (= :job (first effect))
                                          (= :lock (second effect)))
                                 (swap! locks inc)))
                             {:log (tmp ".log")} obs-atom)
            (swap! durations conj (quot (- (System/nanoTime) started) 1000000)))))
      (is (= pid (c-getpid)))
      (is (= 50 @locks))
      (is (= 50 (count @durations)))
      (is (every? #(>= % 350) @durations)
          "every tick accepted a silent client and consumed the 500 ms request budget")
      (is (< (apply max @durations) 1500))
      (finally
        (doseq [client-fd @clients]
          (try (socket/c-close client-fd) (catch Throwable _ nil)))
        (socket/close! fd path)))))

;; --- regression: the main loop must not clobber a socket edit ---------------

(deftest loop-does-not-clobber-socket-edit
  (let [dir (str "/tmp/gts-loop-" (System/nanoTime))
        _ (.mkdirs (java.io.File. dir))
        paths {:state (str dir "/state.edn") :log (str dir "/log") :socket (str dir "/sock")}
        zone (tz/load-zone "America/Los_Angeles")
        ;; a Tuesday noon: a Monday block is well outside the freeze -> :open
        now (* 1000 (tz/local->instant zone (+ (* 86400 (tz/days-from-civil 2026 9 29)) (* 3600 12))))
        clock {:wall-ms now :mono-ns 0 :wall-offset-ms 0 :last-trusted-ms now :confirmed-mono-ns 0}
        state (atom (merge {:schedule sched/empty-schedule :zone "America/Los_Angeles" :clock clock
                            :restore-disablesleep false :zone-pending nil :sleep-refused false
                            :episode nil :last-known-uid nil :active-since-mono nil
                            :last-mono 0 :last-awake 0 :last-tick-awake 0 :last-persist-awake 0
                            :last-sntp-mono 0 :last-supervise-awake 0
                            :version version/value}))
        obs (atom {:mono 0 :awake 0 :wall now :sys-zone "America/Los_Angeles"
                   :sessions [] :capabilities 15 :sleep-disabled false :jobs []})
        exec (fn [_] nil)
        handler (main/make-handler state obs exec paths identity)
        sched1 (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"})
        req (.getBytes (str "{:op :set-schedule :schedule " (pr-str sched1) "}") "UTF-8")]
    (testing "an authorized edit is committed by the handler"
      (handler req 0)                              ; peer uid 0 = root, authorized
      (is (= sched1 (:schedule @state)))
      (is (= sched1 (:schedule (edn/read-string (slurp (:state paths)))))))
    (testing "a following tick does NOT revert it (the bug: stale snapshot overwrote the edit)"
      (main/tick-once! state (atom {}) nil handler exec paths obs)
      (is (= sched1 (:schedule @state)) "in-memory schedule survived the tick")
      (main/tick-once! state (atom {}) nil handler exec paths obs)
      (is (= sched1 (:schedule (edn/read-string (slurp (:state paths))))) "persisted schedule survived"))))
