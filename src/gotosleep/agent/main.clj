(ns gotosleep.agent.main
  "The per-user menu-bar agent with a hidden glitter-uikit settings window. A
  background thread polls the daemon every second, swaps the status into the
  app-state atom, posts the 5-minute warning, and does the secondary relock.
  The window and status item re-render from the atom. This namespace is GUI
  glue; its logic lives in gotosleep.agent.ui and is unit-tested there."
  (:require [gotosleep.agent.ui :as ui]
            [gotosleep.agent.client :as client]
            [gotosleep.agent.log :as agent-log]
            [gotosleep.agent.notify :as notify]
            [gotosleep.agent.relock :as relock]
            [glitter.core :as g]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [jolt.ffi :as ffi]))

(def socket-path "/var/run/gotosleep.sock")
(def agent-log-path
  (str (System/getProperty "user.home") "/Library/Logs/GoToSleep/agent.log"))

(defonce state (atom {:status nil :form nil :panel nil :error nil :notified #{}
                      :form-dirty? false :form-revision 0
                      :schedule-generation 0 :schedule-action-seq 0
                      :uninstall-generation 0 :uninstall-token-seq 0
                      :uninstall-request nil
                      :socket-path socket-path :window nil}))
(defonce status-item (atom nil))

;; --- dispatch (events are data) --------------------------------------------

(defn transition-with!
  "Atomically reduce one event. The CAS loop prevents poll/response/UI
  interleavings from restoring a stale snapshot. Returns the committed reducer
  result plus :before."
  [state-atom reducer event]
  (loop []
    (let [before @state-atom
          result (reducer before event)
          after (:state result)]
      (if (compare-and-set! state-atom before after)
        (assoc result :before before)
        (recur)))))

(defn transition! [state-atom event]
  (transition-with! state-atom ui/handle-event event))

(defn request-current?
  "Whether a queued request is still allowed to use the socket. Schedule and
  uninstall requests each require their exact initiating authority context."
  [state request-context request]
  (case (:op request)
    :set-schedule
    (and (ui/schedule-request-current? state request-context)
         (= (:edit-mode request) (:edit-mode request-context)))

    :uninstall
    (ui/uninstall-request-current? state request-context)

    true))

(defn- uninstall-context-key [request-context]
  (select-keys request-context [:uninstall-generation :uninstall-token]))

(defn- claim-uninstall!
  "Atomically claim a current uninstall token. Claims live for the runner's
  lifetime because generation/token pairs are monotonic and one-shot."
  [claimed request-context]
  (let [key (uninstall-context-key request-context)]
    (loop []
      (let [before @claimed]
        (cond
          (contains? before key) false
          (compare-and-set! claimed before (conj before key)) true
          :else (recur))))))

(defn- schedule-context-key [request-context]
  (mapv request-context [:generation :revision :action-token]))

(defn- claim-schedule!
  "Atomically claim one Save or Confirm request chain before its first I/O."
  [claimed request-context]
  (let [key (schedule-context-key request-context)]
    (when (every? integer? key)
      (loop []
        (let [before @claimed]
          (cond
            (contains? before key) false
            (compare-and-set! claimed before (conj before key)) true
            :else (recur)))))))

(defn process-request-chain!
  "Synchronously execute one queued request and any reducer-produced follow-up.
  Follow-ups retain the initiating form revision and execute before the next
  queued user request. Every send is checked against current authority.
  Schedule generation/revision/action tokens and uninstall generation/tokens
  are atomically claimed before their first I/O."
  ([state-atom send-fn request-context]
   (process-request-chain! state-atom send-fn request-context
                           (atom #{}) (atom #{})))
  ([state-atom send-fn {:keys [request] :as request-context} claimed-uninstalls]
   (process-request-chain! state-atom send-fn request-context
                           claimed-uninstalls (atom #{})))
  ([state-atom send-fn {:keys [request] :as request-context}
    claimed-uninstalls claimed-schedules]
   (let [schedule? (= :set-schedule (:op request))
         uninstall? (= :uninstall (:op request))
         claimed? (cond
                    schedule? (claim-schedule! claimed-schedules request-context)
                    uninstall? (claim-uninstall! claimed-uninstalls request-context)
                    :else true)]
     (when claimed?
       (loop [req request]
         (when (and req (request-current? @state-atom request-context req))
           ;; Claiming and checking are intentionally separate. If authority
           ;; changes while queued work is being claimed, consume the one-shot
           ;; token but drop it before I/O. The daemon owns the final boundary.
           (when (request-current? @state-atom request-context req)
             (let [context (assoc request-context :request req)
                   resp (send-fn req)
                   result (transition! state-atom
                                       [:daemon-response resp context])]
               (recur (:request result))))))))))

(defn with-socket-gate!
  "Run one logical agent socket operation while excluding status polls and
  other request chains. A Save's dry-run and apply follow-up are one operation."
  [socket-gate f]
  (locking socket-gate (f)))

(defn make-request-runner
  ([state-atom send-fn]
   (make-request-runner state-atom send-fn (Object.)))
  ([state-atom send-fn socket-gate]
   {:state-atom state-atom
    :send-fn send-fn
    :socket-gate socket-gate
    :claimed-uninstalls (atom #{})
    :claimed-schedules (atom #{})
    :queue (atom [])
    :running? (atom false)
    :lock (Object.)}))

(defn- take-request! [{:keys [queue running? lock]}]
  (locking lock
    (if-let [item (first @queue)]
      (do (swap! queue subvec 1) item)
      (do (reset! running? false) nil))))

(defn- drain-requests!
  [{:keys [state-atom send-fn socket-gate claimed-uninstalls
           claimed-schedules] :as runner}]
  (loop []
    (when-let [item (take-request! runner)]
      (try
        (with-socket-gate!
         socket-gate
         #(process-request-chain! state-atom send-fn item
                                  claimed-uninstalls claimed-schedules))
        (catch Throwable _
          (transition! state-atom [:daemon-response nil item])))
      (recur))))

(defn submit-request!
  "Append a request in user-event order and ensure exactly one drain worker."
  [{:keys [queue running? lock] :as runner} request-context]
  (let [start? (locking lock
                 (swap! queue conj request-context)
                 (when-not @running?
                   (reset! running? true)
                   true))]
    (when start? (future (drain-requests! runner)))
    nil))

(defonce socket-gate (Object.))
(defonce request-runner
  (make-request-runner state #(client/send-request socket-path %) socket-gate))

(declare show-window!)

(defn apply-action-with!
  "Reduce one shell action, showing the window only when the reducer accepted
  that window action. Carries reducer-provided request authority unchanged."
  [state-atom submit! show! action]
  (let [{next :state req :request request-context :request-context :as result}
        (transition! state-atom action)
        op (first action)
        show? (or (= :open-settings op)
                  (and (= :open-uninstall op)
                       (= :uninstall (get-in next [:panel :kind]))
                       (not= (:panel (:before result)) (:panel next))))]
    (when show? (show!))
    (when req
      (submit!
       (merge {:request req} request-context)))
    result))

(defn- apply-action!
  "Reduce one action vector into the app state. A resulting request goes to
  the daemon on a background thread, and its response is fed back. Actions in
  ui/window-actions also bring the settings window forward."
  [action]
  (apply-action-with!
   state
   #(submit-request! request-runner %)
   #(app/on-gui show-window!)
   action))

(defn dispatch!
  "Registered once with glitter.core/set-dispatch!, which calls it as
  (dispatch! event-map handler-data)."
  [event handler-data]
  (doseq [action (ui/event-actions event handler-data)]
    (apply-action! action)))

;; --- menu bar --------------------------------------------------------------

(defonce ^:private menu-items-shown (atom nil))   ; last rendered item data
(defonce ^:private menu-item-ptrs (atom []))      ; their NSMenuItem pointers

(defn install-owned-menu!
  "Install a +1-owned menu and its +1-owned items, then balance every caller
  ownership retain. NSMenu/status-item retain what they need. Injectable ops
  keep ownership testable without AppKit."
  [status-item menu item-ptrs add-item! set-menu! release!]
  (try
    (doseq [mi item-ptrs] (add-item! menu mi))
    (set-menu! status-item menu)
    (finally
      (doseq [mi item-ptrs] (release! mi))
      (release! menu)))
  item-ptrs)

(defn- configure-menu-item! [mi enabled? action]
  (swap! w/actions dissoc mi)
  (u/control-enabled! mi enabled?)
  (if (and enabled? action)
    (do
      (u/control-target! mi w/invoker)
      (u/control-action! mi (u/sel "fire:"))
      (swap! w/actions assoc-in [mi :click] (fn [_] (apply-action! action))))
    (do
      (u/control-target! mi ffi/null)
      (u/control-action! mi ffi/null)))
  mi)

(defn- menu-item [label enabled? action]
  (let [mi (u/objc-msg-send-3p (u/objc-msg-send-0 (u/cls "NSMenuItem") (u/sel "alloc"))
                               (u/sel "initWithTitle:action:keyEquivalent:")
                               (u/nsstring label) ffi/null (u/nsstring ""))]
    (configure-menu-item! mi enabled? action)))

(defn native-menu-item
  "Return the currently installed native NSMenuItem for `label`, or nil.
  Used by the standalone AppKit smoke to inspect actual native state."
  [label]
  (some (fn [[data ptr]] (when (= label (:label data)) ptr))
        (map vector @menu-items-shown @menu-item-ptrs)))

(defn- refresh-existing-menu-items! [items]
  (let [current-by-label (into {} (map (juxt :label identity) items))]
    (doseq [[shown ptr] (map vector @menu-items-shown @menu-item-ptrs)]
      (if-let [{:keys [enabled? action]} (get current-by-label (:label shown))]
        (configure-menu-item! ptr enabled? action)
        (configure-menu-item! ptr false nil)))))

(defn refresh-menu-actionability!
  "Update installed item enablement and handlers without replacing the menu.
  This restricted operation is safe while NSMenu is running its nested event
  tracking loop; labels and title are reconciled later in default mode."
  []
  (when @status-item
    (refresh-existing-menu-items! (ui/menu-items @state))))

(defn refresh-status-item!
  "Update the title every call; rebuild the menu only when its items changed,
  first disabling/updating already-installed native items in place so a menu
  that is currently tracking cannot retain an actionable stale Uninstall."
  []
  (let [st @state
        item @status-item]
    (when item
      (u/control-title! (u/objc-msg-send-0 item (u/sel "button")) (ui/menu-title st))
      (let [items (ui/menu-items st)]
        (refresh-existing-menu-items! items)
        (when (not= items @menu-items-shown)
          (swap! w/actions #(apply dissoc % @menu-item-ptrs))
          (let [menu (u/objc-msg-send-0 (u/cls "NSMenu") (u/sel "new"))
                ptrs (mapv (fn [{:keys [label enabled? action]}]
                             (menu-item label enabled? action))
                           items)]
            ;; Keep explicit fail-closed state authoritative. Otherwise
            ;; NSMenu's automatic validation may rewrite enabled flags while
            ;; the menu is opening.
            (u/objc-msg-send-1intvoid menu (u/sel "setAutoenablesItems:") 0)
            (install-owned-menu!
             item menu ptrs
             #(u/objc-msg-send-1pvoid %1 (u/sel "addItem:") %2)
             #(u/objc-msg-send-1pvoid %1 (u/sel "setMenu:") %2)
             #(u/objc-msg-send-0void % (u/sel "release")))
            (reset! menu-item-ptrs ptrs)
            (reset! menu-items-shown items)))))))

(defonce event-tracking-mode
  ;; kCFStringEncodingUTF8. A value-equal mode name is valid here; only the
  ;; special kCFRunLoopCommonModes constant requires pointer identity.
  (u/cf-string-create-with-cstring ffi/null "NSEventTrackingRunLoopMode" 134217984))

(defonce ^:private menu-refresh-scheduler
  (let [pending? (atom false)
        perform (ffi/foreign-callable
                 (fn [_]
                   (reset! pending? false)
                   (try
                     (refresh-menu-actionability!)
                     ;; Rebuilding a menu while AppKit is tracking it is not
                     ;; safe. Queue the complete title/menu reconciliation for
                     ;; the default run-loop mode after tracking finishes.
                     (app/schedule! refresh-status-item!)
                     (catch Throwable ex
                       (println "GoToSleep menu refresh failed:"
                                (ex-message ex)))))
                 [:pointer] :void :collect-safe)
        context (ffi/alloc 80)]
    (doseq [offset [0 8 16 24 32 40 48 56 64]]
      (ffi/write context :pointer 0 offset))
    (ffi/write context :pointer perform 72)
    (let [source (u/cf-run-loop-source-create ffi/null 0 context)
          run-loop (u/cf-run-loop-get-main)]
      (u/cf-run-loop-add-source run-loop source (u/default-mode))
      (u/cf-run-loop-add-source run-loop source event-tracking-mode)
      {:pending? pending?
       :source source
       :run-loop run-loop})))

(defn schedule-status-item-refresh!
  "Coalesce a status-item refresh onto the main run loop. The source is
  installed in both default and NSEventTrackingRunLoopMode."
  []
  (let [{:keys [pending? source run-loop]} menu-refresh-scheduler]
    (when (compare-and-set! pending? false true)
      (u/cf-run-loop-source-signal source)
      (u/cf-run-loop-wake-up run-loop)))
  nil)

(defn create-status-item! []
  (let [bar (u/objc-msg-send-0 (u/cls "NSStatusBar") (u/sel "systemStatusBar"))
        item (u/objc-msg-send-1d bar (u/sel "statusItemWithLength:") -1.0)]
    (u/objc-msg-send-0 item (u/sel "retain"))
    (reset! status-item item)
    (refresh-status-item!)))

;; --- window lifecycle -------------------------------------------------------

(def ^:private accessory-policy 1)

(defn- nsapp [] (u/objc-msg-send-0 (u/cls "NSApplication") (u/sel "sharedApplication")))

(defn hide-window-at-launch!
  "Make the run*-created window transparent before run* shows it, then defer
  orderOut: to the first main-loop turn. Injectable for headless tests."
  [win set-alpha! schedule! hide!]
  (set-alpha! win 0.0)
  (schedule! #(hide! win)))

(defn show-window-with!
  "Restore opacity and show the settings window. Injectable for headless tests."
  [win activate! set-alpha! show! front!]
  (activate!)
  (set-alpha! win 1.0)
  (show! win)
  (front! win))

(defn- show-window! []
  (when-let [win (:window @state)]
    (show-window-with!
     win
     #(u/objc-msg-send-1i64void (nsapp) (u/sel "activateIgnoringOtherApps:") 1)
     #(u/objc-msg-send-1dvoid %1 (u/sel "setAlphaValue:") %2)
     u/window-show!
     #(u/objc-msg-send-0void % (u/sel "orderFrontRegardless")))))

;; glitter-uikit's own delegate answers YES to
;; applicationShouldTerminateAfterLastWindowClosed:, so closing Settings would
;; quit the agent. Ours answers NO: closing only hides the window. It also
;; handles application reopen requests so launching the app brings Settings
;; forward when macOS has obscured the status item behind a display notch.
;; The invoker's target/action methods (fire:, tick:) don't depend on being
;; the delegate.
;; Returns (char 0), not 0: jolt's :char return must be a character.
(defonce ^:private keep-running-cb
  (ffi/foreign-callable (fn [_ _ _] (char 0)) [:pointer :pointer :pointer] :char :collect-safe))

(defn handle-reopen-with!
  "Bring Settings forward for one application reopen delegate callback.
  Returns Objective-C YES as a Jolt character."
  [show!]
  (show!)
  (char 1))

(defonce ^:private reopen-cb
  (ffi/foreign-callable
   (fn [_ _ _ _] (handle-reopen-with! show-window!))
   [:pointer :pointer :pointer :char] :char :collect-safe))

(defonce ^:private app-delegate
  (let [existing (u/objc-get-class "GoToSleepAppDelegate")]
    (if (and existing (not (ffi/null? existing)))
      (u/objc-msg-send-0 existing (u/sel "new"))
      (let [c (u/objc-allocate-class-pair (u/cls "NSObject") "GoToSleepAppDelegate" 0)]
        (u/class-add-method c (u/sel "applicationShouldTerminateAfterLastWindowClosed:")
                            keep-running-cb "c@:@")
        (u/class-add-method c (u/sel "applicationShouldHandleReopen:hasVisibleWindows:")
                            reopen-cb "c@:@c")
        (u/objc-register-class-pair c)
        (u/objc-msg-send-0 c (u/sel "new"))))))

(defn- on-activate [win]
  (let [app (nsapp)]
    (u/set-activation-policy! app accessory-policy)
    (u/objc-msg-send-1pvoid app (u/sel "setDelegate:") app-delegate))
  (swap! state assoc :window win)
  (create-status-item!)
  (appkit/mount! win ui/view state)        ; view is state -> hiccup
  ;; The dedicated source also runs while AppKit tracks an already-open menu.
  (add-watch state ::status-item (fn [_ _ _ _] (schedule-status-item-refresh!)))
  ;; run* shows the window right after on-activate, so defer the hide
  (hide-window-at-launch!
   win
   #(u/objc-msg-send-1dvoid %1 (u/sel "setAlphaValue:") %2)
   app/schedule!
   u/window-hide!))

;; --- poller -----------------------------------------------------------------

(defn- autorelease-pool []
  (u/objc-msg-send-0 (u/objc-msg-send-0 (u/cls "NSAutoreleasePool") (u/sel "alloc")) (u/sel "init")))

(defn- daemon-down? [status]
  (or (nil? status) (= :daemon-down status)))

(defn poll-once-with!
  "One fully injectable poll iteration. Tests supply fake status, notification,
  session, lock, clocks, and logging functions, so no OS action is performed."
  [{:keys [state-atom poll-status-fn notify-fn session-fn lock-fn
           mono-ms-fn wall-ms-fn log-fn]}]
  (let [st (poll-status-fn)
        wall-ms (wall-ms-fn)
        {before :before} (transition! state-atom [:status st])
        old-status (:status before)
        down? (daemon-down? st)]
    (cond
      (and (not= :daemon-down old-status) (= :daemon-down st))
      (do (swap! state-atom assoc :daemon-down-since-ms wall-ms)
          (log-fn :daemon-down-begin {}))

      (and (= :daemon-down old-status) (not down?))
      (let [since (:daemon-down-since-ms before)]
        (swap! state-atom dissoc :daemon-down-since-ms)
        (log-fn :daemon-down-end
                (cond-> {} since (assoc :duration-ms (max 0 (- wall-ms since)))))))
    (let [{:keys [post? title body start]} (ui/notify-decision @state-atom)]
      (when post?
        (if (notify-fn title body)
          (do (transition! state-atom [:notification-posted start])
              (log-fn :warning-launched {:start start}))
          (log-fn :warning-launch-failed {:start start}))))
    (let [mono-ms (mono-ms-fn)
          current @state-atom]
      (when (and (ui/should-relock? current)
                 (or (nil? (:last-relock-mono-ms current))
                     (>= (- mono-ms (:last-relock-mono-ms current)) 1000)))
        (let [session (session-fn)]
          (when (ui/should-relock? current session mono-ms)
            ;; Record the attempt before the call, so even a throwing lock path
            ;; stays rate-limited.
            (swap! state-atom assoc :last-relock-mono-ms mono-ms)
            (try
              (let [rc (lock-fn)]
                (log-fn :relock-call {:rc rc}))
              (catch Throwable ex
                (log-fn :relock-failed {:message (ex-message ex)})))))))
    st))

(defn- poll-once! []
  (poll-once-with!
   {:state-atom state
    :poll-status-fn #(with-socket-gate!
                      socket-gate
                      (fn [] (client/poll-status socket-path)))
    :notify-fn notify/post!
    :session-fn relock/current-session
    :lock-fn relock/lock-screen!
    :mono-ms-fn #(quot (System/nanoTime) 1000000)
    :wall-ms-fn #(System/currentTimeMillis)
    :log-fn #(agent-log/append! agent-log-path %1 %2)}))

(defn- start-poller! []
  (doto (Thread.
         (fn []
           (loop []
             (let [pool (autorelease-pool)]
               (try (poll-once!) (catch Throwable _ nil)
                    (finally (u/objc-msg-send-0void pool (u/sel "drain")))))
             (Thread/sleep 1000)
             (recur))))
    (.setDaemon true)
    (.start)))

(defn -main [& _]
  (g/set-dispatch! dispatch!)
  (start-poller!)
  (app/run on-activate :title "Go To Sleep" :width 460 :height 340))
