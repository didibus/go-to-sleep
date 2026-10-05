(ns gotosleep.daemon.main
  "The daemon shell wires the pure core to the real OS. It gathers
  observations, ticks, executes effects, and serves one control-socket
  connection per iteration. Paths and the effect executor are arguments; the
  shipped -main passes fixed values and has no flags."
  (:require [gotosleep.os :as os]
            [gotosleep.protocol :as proto]
            [gotosleep.daemon.core :as core]
            [gotosleep.daemon.store :as store]
            [gotosleep.daemon.log :as log]
            [gotosleep.daemon.jobs :as jobs]
            [gotosleep.daemon.socket :as socket]
            [gotosleep.daemon.power :as power]
            [gotosleep.subprocess :as subprocess]
            [jolt.process :as p])
  (:import [java.io File]))

(def default-paths
  {:state "/Library/Application Support/GoToSleep/state.edn"
   :log "/Library/Logs/GoToSleep/gotosleepd.log"
   :uninstall-log "/Library/Logs/GoToSleep-uninstall.log"
   :socket "/var/run/gotosleep.sock"})

(def daemon-label "com.rubberducking.gotosleep.daemon")
(def package-receipt "com.rubberducking.gotosleep.pkg")

(def default-uninstall-config
  {:agent-label jobs/agent-label
   :agent-plist (:agent-plist jobs/default-config)
   :daemon-label daemon-label
   :daemon-plist "/Library/LaunchDaemons/com.rubberducking.gotosleep.daemon.plist"
   :package-receipt package-receipt})

(def ^:private tick-interval-ns 1000000000)
(def ^:private socket-retry-ns (* 5 1000000000))
(def ^:private uninstall-step-timeout-ms 30000)
(def ^:private persistence-disabled ::persistence-disabled)

(defn- append-log! [path level event data]
  (try
    (log/append! path level event data)
    (catch Throwable _ nil)))

(defn runtime-job-config
  "Resolve host configuration that belongs to the shell, not the pure core."
  [config]
  (assoc config :ntp-server (os/ntp-server)))

(defn observe
  "Build the observation map for a tick from the live OS and pending job
  results."
  [job-results]
  {:mono (os/mono-ns) :awake (os/awake-ns) :wall (os/wall-ms)
   :sys-zone (os/system-zone)
   :sessions (os/sessions)
   :capabilities (os/capabilities)
   :sleep-disabled (os/sleep-disabled?)
   :jobs job-results})

;; --- effect execution ------------------------------------------------------

(defn make-executor
  "An effect executor closing over the runtime: an in-flight-jobs atom, config,
  and paths. Returns (fn [state effect] state') for effects that mutate state
  (:persist is the shell's job; core already updated in-memory state)."
  [inflight config paths]
  (fn [effect]
    (let [[kind & _] effect]
      (case kind
        :job (let [[_ key params] effect]
               (swap! inflight jobs/spawn key params config (System/nanoTime)))
        :sleep-system (let [_ (append-log! (:log paths) :info :sleep-requested {})
                            rc (power/sleep-system!)]
                        (append-log! (:log paths) :info :sleep-system {:rc rc}))
        :log (let [[_ level event data] effect] (append-log! (:log paths) level event data))
        :persist nil          ; handled by the caller, which has the state
        :uninstall nil        ; handled by the caller
        nil))))

(defn persistence-lifecycle
  "Create the production write lifecycle shared by requests, ticks, shutdown,
  and uninstall."
  []
  {:lock (Object.) :phase (atom :running)})

(defn persistence-phase [lifecycle]
  (when lifecycle @(:phase lifecycle)))

(defn- save-running!
  [lifecycle path state]
  (if-not lifecycle
    (do (store/save! path state) :saved)
    (locking (:lock lifecycle)
      (if (= :running @(:phase lifecycle))
        (do (store/save! path state) :saved)
        persistence-disabled))))

(defn mark-uninstall!
  "Disable all future production persistence before teardown starts."
  [lifecycle]
  (when lifecycle
    (locking (:lock lifecycle)
      (reset! (:phase lifecycle) :uninstalling)))
  lifecycle)

(defn- requires-persist? [effect]
  (case (first effect)
    :sleep-system (true? (get-in effect [1 :requires-persist]))
    :job (true? (get-in effect [2 :requires-persist]))
    false))

(defn- deferred-kind [effect]
  (if (= :job (first effect)) (second effect) (first effect)))

(defn run-effects!
  "Execute a tick's effects. :persist writes the current state; :uninstall is
  left to the caller. Individual effect failures are logged and never escape.
  Returns {:persist-failed? bool :deferred #{effect-kinds}}."
  ([effects state exec paths]
   (run-effects! effects state exec paths nil))
  ([effects state exec paths lifecycle]
   (loop [remaining effects persist-ok? false persist-failed? false deferred #{}]
     (if-let [e (first remaining)]
       (case (first e)
         :persist
         (let [result (try
                        (save-running! lifecycle (:state paths) state)
                        (catch Throwable ex
                          (append-log! (:log paths) :error :persist-failed {:msg (ex-message ex)})
                          :failed))]
           (recur (next remaining)
                  (= :saved result)
                  (or persist-failed? (= :failed result))
                  deferred))

         (if (and (requires-persist? e) (not persist-ok?))
           (let [kind (deferred-kind e)]
             (when (= :sleep-system kind)
               (append-log! (:log paths) :warn :sleep-deferred {:reason :persist-failed}))
             (recur (next remaining) persist-ok? persist-failed? (conj deferred kind)))
           (do
             (when-not (= :uninstall (first e))
               (try
                 (exec e)
                 (catch Throwable ex
                   (append-log! (:log paths) :error :effect-failed
                                {:effect (first e) :msg (ex-message ex)}))))
             (recur (next remaining) persist-ok? persist-failed? deferred))))
       {:persist-failed? persist-failed? :deferred deferred}))))

(defn persist-state!
  "Prepare and durably save state using readings taken for this persist.
  Returns the exact state that was written."
  ([path state obs]
   (persist-state! path state obs nil))
  ([path state obs lifecycle]
   (let [prepared (core/prepare-persist state obs)]
     (when (= persistence-disabled (save-running! lifecycle path prepared))
       (throw (ex-info "persistence disabled" {:phase (persistence-phase lifecycle)})))
     prepared)))

(defn- persist-candidate!
  [path state-atom candidate obs lifecycle]
  (if-not lifecycle
    (let [persisted (persist-state! path candidate obs)]
      (reset! state-atom persisted)
      persisted)
    (locking (:lock lifecycle)
      (when-not (= :running @(:phase lifecycle))
        (throw (ex-info "persistence disabled" {:phase @(:phase lifecycle)})))
      (let [persisted (core/prepare-persist candidate obs)]
        (store/save! path persisted)
        (reset! state-atom persisted)
        persisted))))

;; --- uninstall -------------------------------------------------------------

(defn- run-command!
  [argv timeout-ms]
  (try
    (let [proc (p/process (vec argv) {:out :string :err :string})]
      (subprocess/close-input! proc)
      (try
        (let [result (deref proc timeout-ms ::timeout)]
          (if (= ::timeout result)
            (do
              (try (p/destroy proc) (catch Throwable _ nil))
              (try (deref proc 1000 nil) (catch Throwable _ nil))
              {:ok false :exit -1 :out "" :err "timeout" :timed-out true})
            (assoc result :ok (zero? (or (:exit result) -1)) :timed-out false)))
        (finally
          (subprocess/close-pipes! proc))))
    (catch Throwable ex
      {:ok false :exit -1 :out "" :err (str (ex-message ex)) :timed-out false})))

(defn- uninstall-failure! [paths data]
  ;; The normal log directory is itself removed during uninstall. Keep failure
  ;; evidence outside every teardown target so later failures remain visible.
  (append-log! (or (:uninstall-log paths) "/Library/Logs/GoToSleep-uninstall.log")
               :error :uninstall-step-failed data))

(defn- attempt-uninstall-step! [paths runner {:keys [id argv] :as step}]
  (let [result (try
                 (runner argv uninstall-step-timeout-ms)
                 (catch Throwable ex
                   {:ok false :exit -1 :out "" :err (str (ex-message ex)) :timed-out false}))
        result (if (map? result)
                 (assoc result :ok (if (contains? result :ok)
                                     (true? (:ok result))
                                     (and (not (:timed-out result))
                                          (zero? (or (:exit result) -1)))))
                 {:ok false :exit -1 :out "" :err "invalid command result" :timed-out false})]
    (when-not (:ok result)
      (uninstall-failure! paths
                          {:step id :argv argv :exit (:exit result) :err (:err result)
                           :timed-out (boolean (:timed-out result))}))
    (assoc step :result result)))

(defn uninstall!
  "Tear down the install. Only reached when the daemon is in the open state.
  Each step is best-effort; a failure is logged and the rest still run."
  ([paths] (uninstall! paths run-command!))
  ([paths runner]
   (let [support (or (:support paths) "/Library/Application Support/GoToSleep")
         app (or (:app paths) "/Applications/GoToSleep.app")
         logs (or (:logs paths) "/Library/Logs/GoToSleep")
         agent-label (or (:agent-label paths) (:agent-label default-uninstall-config))
         agent-plist (or (:agent-plist paths) (:agent-plist default-uninstall-config))
         daemon-label (or (:daemon-label paths) (:daemon-label default-uninstall-config))
         daemon-plist (or (:daemon-plist paths) (:daemon-plist default-uninstall-config))
         package-receipt (or (:package-receipt paths)
                             (:package-receipt default-uninstall-config))
         raw-sessions (try (os/sessions) (catch Throwable _ :read-failed))
         sessions (if (vector? raw-sessions) raw-sessions :read-failed)
         socket-path (or (:socket paths) "/var/run/gotosleep.sock")
         _ (append-log! (:log paths) :info :uninstall-begin {})
         _ (when (= :read-failed sessions)
             (uninstall-failure! paths {:step :read-sessions :err "read failed" :timed-out false}))
         agent-steps (if (vector? sessions)
                       (->> sessions
                            (filter #(and (map? %) (integer? (:uid %))))
                            (mapv (fn [s]
                                    {:id [:agent-bootout (:uid s)]
                                     :argv ["/bin/launchctl" "bootout"
                                            (str "gui/" (:uid s) "/" agent-label)]})))
                       [])
         steps (into agent-steps
                     [{:id :remove-agent-plist :argv ["/bin/rm" "-rf" agent-plist]}
                      {:id :remove-app :argv ["/bin/rm" "-rf" app]}
                      {:id :remove-logs :argv ["/bin/rm" "-rf" logs]}
                      {:id :remove-socket :argv ["/bin/rm" "-rf" socket-path]}
                      {:id :remove-support :argv ["/bin/rm" "-rf" support]}
                      {:id :forget-package
                       :argv ["/usr/sbin/pkgutil" "--forget" package-receipt]}
                      {:id :remove-daemon-plist :argv ["/bin/rm" "-f" daemon-plist]}
                      {:id :daemon-bootout :argv ["/bin/launchctl" "bootout"
                                                  (str "system/" daemon-label)]}])]
     (mapv #(attempt-uninstall-step! paths runner %) steps))))

;; --- request handling (two-phase) ------------------------------------------

(defn mutation-observation
  "Refresh security-sensitive readings at a mutating request boundary."
  [cached]
  (assoc cached :mono (os/mono-ns) :wall (os/wall-ms) :sessions (os/sessions)))

(defn- execute-request-effects! [effects exec paths]
  (doseq [e effects]
    (when-not (= :uninstall (first e))
      (try
        (exec e)
        (catch Throwable ex
          (append-log! (:log paths) :error :request-effect-failed
                       {:effect (first e) :msg (ex-message ex)}))))))

(defn make-handler
  "The socket handler: parse the request, run core/handle against the current
  state and obs, apply the two-phase persist for accepted edits, execute the
  request's effects, and return response bytes. Uninstall returns an
  :after-write callback which the socket invokes only after a complete write."
  ([state-atom obs-atom exec paths]
   (make-handler state-atom obs-atom exec paths mutation-observation nil))
  ([state-atom obs-atom exec paths observe-mutation]
   (make-handler state-atom obs-atom exec paths observe-mutation nil))
  ([state-atom obs-atom exec paths observe-mutation lifecycle]
   (fn [req-bytes peer-uid]
     (let [{:keys [response after-write]}
           (try
             (let [[status form] (proto/read-request req-bytes)]
               (if (= :bad-request status)
                 {:response {:ok false :error {:code :bad-request :message "Unrecognised request."}}}
                 (let [mutating? (#{:set-schedule :uninstall} (:op form))
                       obs (if mutating? (observe-mutation @obs-atom) @obs-atom)
                       {:keys [candidate response effects]}
                       (core/handle @state-atom form peer-uid obs)]
                   (cond
                     candidate
                     (try
                       (do
                         (persist-candidate! (:state paths) state-atom candidate obs lifecycle)
                         (execute-request-effects! effects exec paths)
                         {:response response})
                       (catch Throwable _
                         {:response {:ok false :error {:code :storage
                                                       :message "Couldn't save the schedule; nothing changed."}}}))

                     (some #(= :uninstall (first %)) effects)
                     {:response response
                      :after-write #(do
                                      (execute-request-effects! effects exec paths)
                                      (mark-uninstall! lifecycle)
                                      (uninstall! paths))}

                     :else
                     (do
                       (execute-request-effects! effects exec paths)
                       {:response response})))))
             (catch Throwable ex
               (append-log! (:log paths) :warn :request-failed {:msg (ex-message ex)})
               {:response {:ok false :error {:code :bad-request :message "Unrecognised request."}}}))
           bytes (.getBytes (str (pr-str response) "\n") "UTF-8")]
       (if after-write
         {:response-bytes bytes :after-write after-write}
         bytes)))))

;; --- the loop --------------------------------------------------------------

(defn tick-once!
  "One iteration: observe, tick, execute effects, then serve one socket
  connection. The state atom is the single source of truth — the tick commits
  its result to the atom, and the socket handler may then commit an accepted
  edit to the same atom. Nothing returns-and-overwrites, so a socket edit is
  never clobbered by a stale tick snapshot."
  ([state-atom inflight listen-fd handler exec paths obs-atom]
   (tick-once! state-atom inflight listen-fd handler exec paths obs-atom nil))
  ([state-atom inflight listen-fd handler exec paths obs-atom lifecycle]
   (when (or (nil? lifecycle) (= :running (persistence-phase lifecycle)))
     (let [[job-results inflight']
        (try
          (jobs/poll @inflight (System/nanoTime))
          (catch Throwable ex
            (append-log! (:log paths) :error :job-poll-failed {:msg (ex-message ex)})
            [[] @inflight]))
        obs (try
              (observe job-results)
              (catch Throwable ex
                (append-log! (:log paths) :error :observe-failed {:msg (ex-message ex)})
                nil))]
    (when obs
      (reset! inflight inflight')
      (reset! obs-atom obs)
      (let [previous @state-atom
            {next :state effects :effects}
            (try
              (core/tick previous obs)
              (catch Throwable ex
                (append-log! (:log paths) :error :tick-failed {:msg (ex-message ex)})
                {:state @state-atom :effects []}))]
        (reset! state-atom next)
        (let [{:keys [persist-failed? deferred]}
              (try
                (run-effects! effects next exec paths lifecycle)
                (catch Throwable ex
                  (append-log! (:log paths) :error :effect-loop-failed {:msg (ex-message ex)})
                  {:persist-failed? true :deferred #{}}))]
          (when (seq deferred)
            (swap! state-atom #(core/reconcile-deferred-effects previous % deferred)))
          (when persist-failed?
            (swap! state-atom assoc :last-persist-awake nil)))))
    (when listen-fd
      (try
        (socket/serve-one! listen-fd handler 100)
        (catch Throwable ex
          (append-log! (:log paths) :warn :socket-serve-failed {:msg (ex-message ex)}))))
       nil))))

(defn shutdown!
  "The clean-shutdown body used by the SIGTERM hook."
  ([state-atom paths]
   (shutdown! state-atom paths nil))
  ([state-atom paths lifecycle]
   (let [body (fn []
                (if (and lifecycle (not= :running @(:phase lifecycle)))
                  true
                  (do
                    (when lifecycle (reset! (:phase lifecycle) :shutting-down))
                    (try
                      (let [persisted (core/prepare-persist
                                       @state-atom
                                       {:wall (os/wall-ms) :mono (os/mono-ns)})]
                        (store/save! (:state paths) persisted)
                        (reset! state-atom persisted)
                        true)
                      (catch Throwable ex
                        (append-log! (:log paths) :error :shutdown-persist-failed
                                     {:msg (ex-message ex)})
                        false)
                      (finally
                        (when lifecycle (reset! (:phase lifecycle) :shutdown)))))))]
     (if lifecycle
       (locking (:lock lifecycle) (body))
       (body)))))

(defn run!
  "Run the daemon loop with the given paths and config. Blocks forever. A
  SIGTERM shutdown hook persists the latest state first."
  [paths config]
  (let [config (runtime-job-config config)
        [persisted load-event] (store/load! (:state paths))
        start-obs (observe [])
        {state0 :state init-fx :effects} (core/init persisted (os/boot-session) start-obs)
        state (atom state0)
        lifecycle (persistence-lifecycle)
        inflight (atom {})
        obs-atom (atom start-obs)
        exec (make-executor inflight config paths)
        handler (make-handler state obs-atom exec paths mutation-observation lifecycle)
        listen-fd (try (socket/bind-listen! (:socket paths))
                       (catch Throwable ex
                         (append-log! (:log paths) :warn :socket-bind-failed {:msg (ex-message ex)}) nil))]
    (run-effects! init-fx @state exec paths lifecycle)
    (case load-event
      :quarantined
      (append-log! (:log paths) :warn :state-quarantined {})

      :quarantine-failed
      (append-log! (:log paths) :warn :state-quarantine-failed
                   {:fallback :empty-schedule})

      nil)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (shutdown! state paths lifecycle))))
    (loop [fd listen-fd next-bind-ns (System/nanoTime)]
      (let [started (System/nanoTime)]
        (tick-once! state inflight fd handler exec paths obs-atom lifecycle)
        (let [now (System/nanoTime)
              retry? (and (= :running (persistence-phase lifecycle))
                          (nil? fd) (>= now next-bind-ns))
              fd' (if retry?
                    (try
                      (socket/bind-listen! (:socket paths))
                      (catch Throwable ex
                        (append-log! (:log paths) :warn :socket-bind-failed {:msg (ex-message ex)})
                        nil))
                    fd)
              next-bind-ns' (if (and retry? (nil? fd')) (+ now socket-retry-ns) next-bind-ns)
              remaining (- tick-interval-ns (- (System/nanoTime) started))]
          (when (pos? remaining)
            (Thread/sleep (max 1 (quot remaining 1000000))))
          (recur fd' next-bind-ns'))))))

(defn -main [& _]
  (run! default-paths jobs/default-config))
