(ns gotosleep.daemon.core
  "The daemon's pure functional core. `init`, `tick`, and `handle` take the
  current state plus observations and return the next state and a list of
  effects. The shell executes effects and feeds their results back as `:jobs`
  on the next tick.

  Times: `obs` carries `:mono`/`:awake` in nanoseconds and `:wall` in ms;
  internally we work in ms. Instants in state and effects are epoch ms."
  (:require [gotosleep.schedule :as sched]
            [gotosleep.clock :as clock]
            [gotosleep.tz :as tz]
            [gotosleep.protocol :as proto]
            [gotosleep.version :as version]))

(def ^:private day-ms sched/day-ms)
(def ^:private lock-min-elapsed 0)
(def ^:private sleep-min-elapsed 5000)
(def ^:private sleep-repeat 10000)
(def ^:private logout-min-elapsed 15000)
(def ^:private logout-repeat 30000)
(def ^:private safety-stop-ms (* 24 60 60 1000))
(def ^:private persist-interval 60000)
(def ^:private sntp-interval (* 60 60 1000))
(def ^:private sntp-suspect-interval (* 5 60 1000))
(def ^:private supervise-frozen 5000)
(def ^:private supervise-open 60000)
(def ^:private sleep-detect-gap 3000)
(def ^:private tick-late-ns (* 2 1000000000))
(def ^:private failure-log-interval-ns (* 60 60 1000000000))

(defn- ->ms [ns] (quot ns 1000000))

(defn- zone-obj [state] (or (tz/load-zone (:zone state)) tz/utc))

(defn now-ms
  "Trusted now in ms from state's clock and the monotonic reading."
  [state obs]
  (clock/trusted-now (:clock state) (:mono obs)))

(defn- active-occurrence-key [occ]
  (when occ [(:day occ) (:date occ)]))

(defn- active-tracking
  "Return occurrence-scoped safety timing for `active-occ`. Same-occurrence
  timing keeps its monotonic anchor. A new boot reconstructs that anchor from
  the persisted trusted instant; a new occurrence starts from its own start."
  [state active-occ now mono]
  (when active-occ
    (let [key (active-occurrence-key active-occ)
          same? (= key (:active-occurrence-key state))
          persisted-trusted (when same? (:active-since-trusted-ms state))
          since-trusted (or persisted-trusted (:start active-occ))
          since-mono (if (and same? (integer? (:active-since-mono state)))
                       (:active-since-mono state)
                       (- mono (* 1000000 (max 0 (- now since-trusted)))))]
      {:active-occurrence-key key
       :active-since-mono since-mono
       :active-since-trusted-ms since-trusted})))

(defn- set-active-tracking [state tracking]
  (let [fields [:active-occurrence-key :active-since-mono :active-since-trusted-ms]]
    (if tracking
      (-> state
          (merge tracking)
          (update :clock merge tracking))
      (-> state
          (assoc :active-occurrence-key nil
                 :active-since-mono nil
                 :active-since-trusted-ms nil)
          (update :clock #(apply dissoc % fields))))))

(defn- safety-stop-reached?
  [tracking mono]
  (and (integer? (:active-since-mono tracking))
       (>= (- mono (:active-since-mono tracking)) (* 1000000 safety-stop-ms))))

(defn prepare-persist
  "Return the exact state to persist for the supplied wall/monotonic readings.
  The shell uses this for request and shutdown writes; tick uses it before
  returning any :persist effect."
  [state obs]
  (update state :clock clock/on-persist (:wall obs) (:mono obs)))

;; --- effects helpers -------------------------------------------------------

(defn- log [level event data] [:log level event data])

(def job-timeouts
  {:lock 3000 :disablesleep 5000 :logout 10000 :sntp 10000 :restore-sleep 5000})

(defn- agent-timeout [kind] (case kind :agent-print 5000 :agent-bootstrap 10000 :agent-kickstart 5000))

;; --- status ----------------------------------------------------------------

(defn status-response
  [state obs]
  (let [z (zone-obj state)
        now (now-ms state obs)
        sst (sched/state (:schedule state) z now)
        active-tracking (active-tracking state (first (:active sst)) now (:mono obs))
        frozen (:frozen sst)
        upcoming (->> (sched/occurrences (:schedule state) z now (+ now (* 8 sched/week-ms)))
                      (remove (set frozen))
                      (take 7))
        occs (->> (concat frozen upcoming)
                  (sort-by :start)
                  (mapv #(proto/occ->wire z now %)))]
    {:ok true
     :state (:state sst)
     :now now
     :zone (:zone state)
     :zone-pending (:zone-pending state)
     :clock-suspect (clock/suspect? (:clock state) (:wall obs) (:mono obs))
     :safety-stopped (boolean (safety-stop-reached? active-tracking (:mono obs)))
     :sleep-refused (boolean (:sleep-refused state))
     :unfrozen-at (:unfrozen-at sst)
     :unfrozen-label (when (:unfrozen-at sst) (proto/format-time z (:unfrozen-at sst)))
     :schedule (:schedule state)
     :occurrences occs
     :version version/value}))

;; --- handle ----------------------------------------------------------------

(defn- authorized? [peer-uid obs]
  (or (= 0 peer-uid)
      (when-let [s (and (vector? (:sessions obs))
                        (first (filter :on-console? (:sessions obs))))]
        (= peer-uid (:uid s)))))

(defn- accepted-schedule-state
  "Build the one candidate persisted for an accepted schedule request,
  including occurrence-scoped active tracking."
  [state schedule z now mono]
  (let [before (sched/state (:schedule state) z now)
        after (sched/state schedule z now)
        baseline-active (first (:active before))
        candidate-active (first (:active after))
        candidate (cond-> (assoc state :schedule schedule)
                    (and candidate-active (not baseline-active))
                    (assoc :episode nil :sleep-refused false :sleep-ready false))
        tracking (cond
                   baseline-active
                   (active-tracking state baseline-active now mono)

                   candidate-active
                   {:active-occurrence-key (active-occurrence-key candidate-active)
                    :active-since-mono mono
                    :active-since-trusted-ms now}

                   :else nil)]
    (set-active-tracking candidate tracking)))

(defn handle
  "Handles one request. Returns {:candidate state-or-nil :response map
  :effects [...]}. When :candidate is non-nil the shell must persist it before
  committing and sending :response; a persist failure sends :storage instead."
  [state {:keys [op] :as request} peer-uid obs]
  (let [z (zone-obj state)
        now (now-ms state obs)
        err (fn [e] {:candidate nil :response (proto/error-response z now e) :effects []})]
    (case op
      :status
      {:candidate nil :response (status-response state obs) :effects []}

      :set-schedule
      (if-not (authorized? peer-uid obs)
        (err {:code :forbidden})
        (let [{:keys [schedule dry-run]} request
              res (sched/check-edit (:schedule state) schedule z now
                                    (select-keys request
                                                 [:dry-run :edit-mode
                                                  :confirm-freeze :confirm-growth]))]
          (cond
            (not (:ok res)) (err (:error res))
            dry-run {:candidate nil
                     :response (if (= :growth-only (:edit-mode res))
                                 (let [token (:confirm-growth res)]
                                   {:ok true :applied false
                                    :edit-mode :growth-only
                                    :confirm-growth token
                                    :confirm-until-label
                                    (proto/format-time z (:confirm-until-at token))
                                    :activates-now (:activates-now token)})
                                 (cond-> {:ok true :applied false
                                          :edit-mode :open
                                          :freezes-now (mapv #(proto/occ->wire z now %)
                                                             (:freezes-now res))}
                                   (seq (:freezes-now res))
                                   (assoc :confirm-until-label
                                          (proto/format-time
                                           z (apply max (map :end (:freezes-now res)))))))
                     :effects []}
            :else
            (let [candidate (accepted-schedule-state state schedule z now (:mono obs))]
              {:candidate candidate
               :response {:ok true :applied true
                          :edit-mode (:edit-mode res)
                          :status (status-response candidate obs)}
               :effects [(log :info :schedule-set {:schedule schedule})]}))))

      :uninstall
      (if-not (authorized? peer-uid obs)
        (err {:code :forbidden})
        (let [sst (sched/state (:schedule state) z now)]
          (if (= :open (:state sst))
            {:candidate nil :response {:ok true}
             :effects [(log :info :uninstall {}) [:uninstall]]}
            (err {:code :frozen
                  :operation :uninstall
                  :unfrozen-at (:unfrozen-at sst)}))))

      (err {:code :bad-request}))))

;; --- init ------------------------------------------------------------------

(def ^:private runtime-defaults
  {:zone-pending nil :sleep-refused false :episode nil :last-known-uid nil
   :active-occurrence-key nil :active-since-mono nil :active-since-trusted-ms nil
   :last-mono nil :last-awake nil :last-tick-awake nil
   :last-persist-awake nil :last-sntp-mono nil :last-supervise-awake nil
   :logged-bad-zone nil :restore-disablesleep false
   :disablesleep-pending false :sleep-ready false :restore-sleep-pending false
   :clock-suspect-active false :failure-log-times {}})

(defn init
  "The daemon's initial state and startup effects: start the trusted clock,
  choose the effective zone, and log an enforcement gap if a block was missed
  while the daemon wasn't running across a reboot."
  [persisted boot-session obs]
  (let [{:keys [clock event]} (clock/start (:clock persisted) boot-session (:wall obs) (:mono obs))
        zone (or (:zone persisted)
                 (when (tz/valid-zone? (:sys-zone obs)) (:sys-zone obs))
                 "UTC")
        persisted-clock (:clock persisted)
        state (merge runtime-defaults persisted
                     {:clock clock :zone zone :version version/value
                      :schedule (or (:schedule persisted) sched/empty-schedule)
                      :active-occurrence-key (:active-occurrence-key persisted-clock)
                      :active-since-mono (when (= :resumed event)
                                           (:active-since-mono persisted-clock))
                      :active-since-trusted-ms (:active-since-trusted-ms persisted-clock)})
        now (now-ms state obs)
        z (zone-obj state)
        current-active (first (:active (sched/state (:schedule state) z now)))
        state (set-active-tracking state (active-tracking state current-active now (:mono obs)))
        gap-fx (when (and (#{:new-boot :new-boot-backwards} event)
                          (:last-trusted-ms (:clock persisted)))
                 (let [lt (:last-trusted-ms (:clock persisted))
                       missed (->> (sched/occurrences (:schedule state) z
                                                     (- lt sched/occurrence-lookback-ms) now)
                                   (filter #(and (> (:end %) lt) (< (:start %) now))))]
                   (when (seq missed)
                     [(log :warn :enforcement-gap {:from lt :to now :occurrences (count missed)})])))]
    {:state state
     :effects (into [(log :info :clock-start {:event event :now now})] (or gap-fx []))}))

;; --- tick helpers ----------------------------------------------------------

(defn- job-kind [key] (if (vector? key) (first key) key))

(defn- job-success? [{:keys [exit timed-out]}]
  (and (not timed-out) (zero? (or exit -1))))

(defn- result-data [{:keys [key exit out err timed-out]}]
  {:key key :exit exit :out (or out "") :err (or err "")
   :timed-out (boolean timed-out)})

(defn- agent-problem [{:keys [key out] :as job}]
  (let [kind (job-kind key)]
    (cond
      (not (job-success? job)) [:failed kind (:exit job) (:err job) (:timed-out job)]
      (and (= :agent-print kind)
           (not (and (string? out) (re-find #"state = running" out))))
      [:not-running kind out]
      :else nil)))

(defn- log-agent-result
  "Log repair results, suppressing an identical repeated failure for one hour."
  [state fx job mono]
  (if-let [problem (agent-problem job)]
    (let [signature [(:key job) problem]
          last-at (get (:failure-log-times state) signature)
          due? (or (nil? last-at) (>= (- mono last-at) failure-log-interval-ns))]
      [(if due? (assoc-in state [:failure-log-times signature] mono) state)
       (if due? (conj fx (log :warn :agent-job-result (result-data job))) fx)])
    (if (= :agent-print (job-kind (:key job)))
      [state fx]
      [state (conj fx (log :info :agent-job-result (result-data job)))])))

(defn- apply-job-results
  "Fold job results into state. Power-setting transitions expose explicit
  persist effects so durable restore intent precedes a sleep request."
  [state obs]
  (reduce
   (fn [[st fx] {:keys [key exit out] :as job}]
     (let [kind (job-kind key)
           success? (job-success? job)]
       (cond
         (= kind :sntp)
         (if-let [delta (clock/parse-sntp (or exit 0) out)]
           (let [{:keys [clock confirmed?]} (clock/confirm (:clock st) (:wall obs) (:mono obs) delta)]
             [(assoc st :clock clock)
              (cond-> (conj fx (log :info :sntp {:delta-ms delta :confirmed? confirmed?}))
                confirmed? (conj [:persist]))])
           [st fx])

         (= kind :disablesleep)
         (let [st (assoc st :disablesleep-pending false :sleep-ready success?)
               fx (conj fx (log (if success? :info :warn) :job-result (result-data job)))]
           [st fx])

         (= kind :restore-sleep)
         (let [changed? (and success? (:restore-disablesleep st))
               st (cond-> (assoc st :restore-sleep-pending false)
                    success? (assoc :restore-disablesleep false))
               fx (conj fx (log (if success? :info :warn) :job-result (result-data job)))]
           [st (cond-> fx changed? (conj [:persist]))])

         (#{:lock :logout} kind)
         [st (conj fx (log (if success? :info :warn) :job-result (result-data job)))]

         (#{:agent-print :agent-bootstrap :agent-kickstart} kind)
         (log-agent-result st fx job (:mono obs))

         :else [st fx])))
   [state []]
   (:jobs obs)))

(defn- clock-suspect-transition [state obs]
  (let [suspect? (clock/suspect? (:clock state) (:wall obs) (:mono obs))
        entering? (and suspect? (not (:clock-suspect-active state)))
        trusted (now-ms state obs)]
    [(assoc state :clock-suspect-active suspect?)
     (if entering?
       [(log :warn :clock-suspect
             {:wall-ms (:wall obs) :trusted-ms trusted :difference-ms (- (:wall obs) trusted)})]
       [])]))

(defn- update-last-uid [state obs]
  (if-let [c (and (vector? (:sessions obs)) (first (filter :on-console? (:sessions obs))))]
    (assoc state :last-known-uid (:uid c))
    state))

(defn- adopt-zone
  "Zone adoption: adopt sys-zone when it keeps every frozen block contained and
  preserves the schedule window invariant, otherwise defer with :zone-pending.
  Returns [state effects persist?]."
  [state obs z now]
  (let [sys (:sys-zone obs)]
    (cond
      (nil? sys)
      [state [] false]

      (= sys (:zone state))
      [(assoc state :zone-pending nil) [] false]

      (not (tz/valid-zone? sys))
      (if (= sys (:logged-bad-zone state))
        [state [] false]
        [(assoc state :logged-bad-zone sys) [(log :warn :invalid-zone {:zone sys})] false])

      (sched/zone-adoptable? (:schedule state) z (tz/load-zone sys) now)
      [(assoc state :zone sys :zone-pending nil)
       [(log :info :zone-adopted {:from (:zone state) :to sys})] true]

      :else
      (if (= sys (:zone-pending state))
        [state [] false]
        [(assoc state :zone-pending sys) [(log :info :zone-pending {:zone sys})] false]))))

(defn- track-active
  "Maintain occurrence-scoped, persisted active timing and safety-stop state.
  Returns [state safety-stopped? changed?]."
  [state active-occ now mono]
  (let [tracking (active-tracking state active-occ now mono)
        before (select-keys state
                            [:active-occurrence-key
                             :active-since-mono
                             :active-since-trusted-ms])]
    [(set-active-tracking state tracking)
     (boolean (safety-stop-reached? tracking mono))
     (not= before
           (or tracking
               {:active-occurrence-key nil
                :active-since-mono nil
                :active-since-trusted-ms nil}))]))

(defn- detect-sleep?
  "Whether the machine slept between the last tick and now: monotonic time
  (which counts through sleep) advanced much more than awake time (which
  stops). `mono` and `awake` are raw ns."
  [state mono awake]
  (boolean
   (and (:last-mono state) (:last-awake state)
        (let [mono-d (->ms (- mono (:last-mono state)))
              awake-d (->ms (- awake (:last-awake state)))]
          (> (- mono-d awake-d) sleep-detect-gap)))))

;; --- fallback chain --------------------------------------------------------

(defn- clear-episode [state]
  (assoc state :episode nil :sleep-refused false :sleep-ready false))

(defn- sleep-effect
  "A sleep after changing SleepDisabled is conditional on a successful
  persist earlier in the same effect sequence."
  [state]
  (if (:restore-disablesleep state)
    [:sleep-system {:requires-persist true}]
    [:sleep-system]))

(defn reconcile-deferred-effects
  "Undo attempt-only runtime state for effects the shell did not execute.
  Durable restore intent is deliberately retained so the next tick retries."
  [previous state deferred]
  (let [state (if (contains? deferred :disablesleep)
                (assoc state :disablesleep-pending false :sleep-ready false)
                state)]
    (if (contains? deferred :sleep-system)
      (let [before (:episode previous)
            after (:episode state)
            same-episode? (and before after (= (:uid before) (:uid after)))]
        (if after
          (assoc state :episode
                 (assoc after
                        :first-sleep (when same-episode? (:first-sleep before))
                        :last-sleep (when same-episode? (:last-sleep before))))
          state))
      state)))

(defn- finish-block
  "End the unlocked episode and restore the user's SleepDisabled setting.
  Restore intent remains durable until the restore job succeeds."
  [state obs]
  (let [state (clear-episode state)]
    (cond
      (not (:restore-disablesleep state))
      [state [] false]

      ;; A pre-crash pmset may still complete after an observation reports
      ;; true, so observations alone never discharge durable restore intent.
      (:disablesleep-pending state)
      [state [] false]

      (:restore-sleep-pending state)
      [state [] false]

      :else
      [(assoc state :restore-sleep-pending true)
       [[:job :restore-sleep {}] (log :info :restore-sleep-requested {})]
       false])))

(defn- fresh-episode [uid awake]
  {:uid uid :elapsed 0 :last-awake awake :last-sleep nil :first-sleep nil
   :slept? false :last-logout {}})

(defn- episode-for-target [state uid awake slept-this-tick?]
  (let [old (:episode state)
        ep (if (and old (= uid (:uid old))) old (fresh-episode uid awake))]
    (cond-> ep
      (and (:first-sleep ep) slept-this-tick?) (assoc :slept? true))))

(defn- enforce
  "The fallback chain while active. Returns [state effects persist?].
  `awake`/`mono` are raw ns; the episode clock counts full-wake awake time
  only."
  [state obs sst now awake mono slept-this-tick?]
  (if-not (= :active (:state sst))
    (finish-block state obs)
    (let [sessions (:sessions obs)
          read-failed? (= :read-failed sessions)
          console (when (vector? sessions) (first (filter :on-console? sessions)))
          full-wake? (let [c (:capabilities obs)] (if (integer? c) (pos? (bit-and c 2)) true))
          target-uid (if read-failed? (:last-known-uid state) (:uid console))
          unlocked? (if read-failed? true (and console (not (:locked? console))))]
      (cond
        ;; locked, or the login window (read ok, no on-console session): nothing
        (and (not read-failed?) (or (nil? console) (:locked? console)))
        [(clear-episode state) [] false]

        (not unlocked?) [state [] false]

        ;; dark wake: hold the episode, advance nothing, emit nothing
        (not full-wake?)
        [(assoc state :episode (episode-for-target state target-uid awake slept-this-tick?)) [] false]

        :else
        (let [old (:episode state)
              same-target? (and old (= target-uid (:uid old)))
              started? (not same-target?)
              ep0 (episode-for-target state target-uid awake slept-this-tick?)
              elapsed (if started? 0 (+ (:elapsed ep0) (max 0 (->ms (- awake (:last-awake ep0))))))
              ep (assoc ep0 :elapsed elapsed :last-awake awake)
              fx (atom [])
              st (atom (assoc state :episode ep))]
          ;; lock, every tick while unlocked
          (when target-uid
            (swap! fx conj [:job :lock {:uid target-uid}])
            (swap! fx conj (log :info :lock-requested {:uid target-uid})))
          ;; sleep step at >= 5 s, repeating at most every 10 s
          (let [sleep-due? (and (>= elapsed sleep-min-elapsed)
                                (or (nil? (:last-sleep ep))
                                    (>= (- elapsed (:last-sleep ep)) sleep-repeat)))]
            (cond
              (and sleep-due? (:sleep-ready @st) (not (true? (:sleep-disabled obs))))
              (do
                (swap! st assoc :sleep-ready false)
                (swap! fx conj (sleep-effect @st))
                (swap! st update :episode assoc :last-sleep elapsed
                       :first-sleep (or (:first-sleep ep) elapsed)))

              (and sleep-due? (true? (:sleep-disabled obs)))
              (when-not (:disablesleep-pending @st)
                ;; Record the original true setting before a crash can occur
                ;; between pmset and the next daemon tick. If pmset never runs,
                ;; observing true at block end safely clears this intent.
                (swap! st assoc :restore-disablesleep true
                       :disablesleep-pending true :sleep-ready false)
                (swap! fx conj [:persist])
                (swap! fx conj [:job :disablesleep {:requires-persist true}])
                (swap! fx conj (log :info :disablesleep-requested {})))

              sleep-due?
              (do
                (swap! fx conj (sleep-effect @st))
                (swap! st update :episode assoc :last-sleep elapsed
                       :first-sleep (or (:first-sleep ep) elapsed)))))
          ;; logout at >= 15 s if the machine refused to sleep, once per 30 s per uid
          (let [ep* (:episode @st)]
            (when (and target-uid
                       (>= elapsed logout-min-elapsed)
                       (:first-sleep ep*)
                       (> elapsed (:first-sleep ep*))
                       (not (:slept? ep*))
                       (let [last (get (:last-logout ep*) target-uid)]
                         (or (nil? last) (>= (- elapsed last) logout-repeat))))
              (swap! fx conj [:job :logout {:uid target-uid}])
              (swap! fx conj (log :warn :logout-requested {:uid target-uid}))
              (swap! st assoc :sleep-refused true)
              (swap! st update-in [:episode :last-logout] assoc target-uid elapsed)))
          [@st @fx false])))))

(defn- ensure-sleep-persist-barrier
  "Insert a persist before the first sleep that depends on durable restore
  intent, unless an earlier persist is already present."
  [effects]
  (loop [remaining effects out [] persist-before? false]
    (if-let [effect (first remaining)]
      (cond
        (= :persist (first effect))
        (recur (next remaining) (conj out effect) true)

        (and (= :sleep-system (first effect))
             (true? (get-in effect [1 :requires-persist]))
             (not persist-before?))
        (recur remaining (conj out [:persist]) true)

        :else
        (recur (next remaining) (conj out effect) persist-before?))
      (vec out))))

;; --- supervision -----------------------------------------------------------

(defn- supervise
  "Restart the per-user agent. On cadence, print each logged-in agent's status;
  react to prior print results by bootstrapping (exit 113) or kickstarting.
  Enforcement never depends on this."
  [state obs sst awake]
  (let [interval (if (#{:frozen :active} (:state sst)) supervise-frozen supervise-open)
        due? (or (nil? (:last-supervise-awake state))
                 (>= (->ms (- awake (:last-supervise-awake state))) interval))
        react (fn [fx {:keys [key exit out]}]
                (if (and (vector? key) (= :agent-print (first key)))
                  (let [uid (second key)]
                    (cond
                      (= 113 exit) (conj fx
                                         [:job [:agent-bootstrap uid] {:uid uid}]
                                         (log :info :agent-bootstrap-requested {:uid uid}))
                      (and (zero? (or exit 0)) (string? out)
                           (not (re-find #"state = running" out)))
                      (conj fx
                            [:job [:agent-kickstart uid] {:uid uid}]
                            (log :info :agent-kickstart-requested {:uid uid}))
                      :else fx))
                  fx))
        react-fx (reduce react [] (:jobs obs))
        uids (when (vector? (:sessions obs))
               (->> (:sessions obs) (filter :login-done?) (map :uid) distinct))
        print-fx (when due? (mapv (fn [uid] [:job [:agent-print uid] {:uid uid}]) uids))]
    [(if due? (assoc state :last-supervise-awake awake) state)
     (into (vec react-fx) (or print-fx []))]))

;; --- sntp cadence ----------------------------------------------------------

(defn- sntp-tick
  "Emit an sntp query at start, hourly, and every 5 min while suspect."
  [state obs mono]
  (let [suspect? (clock/suspect? (:clock state) (:wall obs) (:mono obs))
        interval (if suspect? sntp-suspect-interval sntp-interval)
        due? (or (nil? (:last-sntp-mono state))
                 (>= (->ms (- mono (:last-sntp-mono state))) interval))]
    (if due?
      [(assoc state :last-sntp-mono mono) [[:job :sntp {}]]]
      [state []])))

;; --- tick ------------------------------------------------------------------

(defn tick
  "One steady-state tick. Returns {:state s :effects [...]}."
  [state obs]
  (let [awake (:awake obs)
        mono (:mono obs)
        slept? (detect-sleep? state mono awake)
        [state jfx] (apply-job-results state obs)
        [state cfx] (clock-suspect-transition state obs)
        state (update-last-uid state obs)
        now (now-ms state obs)
        z (zone-obj state)
        late-fx (when (and (:last-tick-awake state)
                           (> (- awake (:last-tick-awake state)) tick-late-ns))
                  [(log :warn :tick-late {:gap-ms (->ms (- awake (:last-tick-awake state)))})])
        [state zfx zp?] (adopt-zone state obs z now)
        z (zone-obj state)
        sst (sched/state (:schedule state) z now)
        [state safety? active-changed?] (track-active state (first (:active sst)) now mono)
        [state efx ep?] (if safety?
                          [(assoc state :episode nil) [(log :warn :safety-stop {})] false]
                          (enforce state obs sst now awake mono slept?))
        [state sfx] (supervise state obs sst awake)
        [state qfx] (sntp-tick state obs mono)
        persist-due? (or (nil? (:last-persist-awake state))
                         (>= (->ms (- awake (:last-persist-awake state))) persist-interval))
        effects (ensure-sleep-persist-barrier
                 (vec (concat jfx cfx (or late-fx []) zfx efx sfx qfx)))
        explicit-persist? (boolean (some #(= :persist (first %)) effects))
        persist? (boolean (or zp? ep? active-changed? persist-due? explicit-persist?))
        state (cond-> (assoc state :last-mono mono :last-awake awake :last-tick-awake awake)
                persist? (assoc :last-persist-awake awake))
        state (if persist? (prepare-persist state obs) state)
        effects (if (and persist? (not explicit-persist?)) (conj effects [:persist]) effects)]
    {:state state :effects effects}))
