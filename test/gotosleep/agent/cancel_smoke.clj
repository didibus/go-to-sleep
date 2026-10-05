(ns gotosleep.agent.cancel-smoke
  "Standalone live, non-enforcing AppKit smoke for schedule editing,
  confirmation, authority reconciliation, and uninstall availability.

  Run explicitly in a GUI session with:

    jolt -A:test -m gotosleep.agent.cancel-smoke

  Every daemon response and status is synthetic. This namespace never opens
  the daemon socket, runs the lock helper, or invokes a system action."
  (:require [glitter.core :as g]
            [glitter-uikit.app :as app]
            [glitter-uikit.appkit :as appkit]
            [glitter-uikit.ffi :as u]
            [glitter-uikit.widget :as w]
            [gotosleep.agent.main :as main]
            [gotosleep.agent.ui :as ui]
            [gotosleep.schedule :as sched]
            [jolt.ffi :as ffi]))

(def ^:private growth-banner
  "Schedule is frozen until Thu 06:15. Blocks may be added or made longer; shrinking or moving waits until afterward.")

(def ^:private growth-noop
  "Add a block or make an existing block longer to save.")

(def ^:private growth-invalid
  "While frozen, existing blocks must keep the same or an earlier start and the same or a later end.")

(def ^:private immediate-lock-message
  "Confirming will lock the screen immediately and keep it locked until Thu 08:30.")

(defn- root-stack [window]
  (u/array-get (u/objc-msg-send-0 (u/window-content window)
                                  (u/sel "subviews"))
               0))

(defn- constraint-count [view]
  (u/array-count (u/objc-msg-send-0 view (u/sel "constraints"))))

(defn- control-enabled? [view]
  (not= (char 0) (u/objc-msg-send-0char view (u/sel "isEnabled"))))

(defn- clear-first-responder! [window]
  (u/objc-msg-send-1pchar window (u/sel "makeFirstResponder:") ffi/null))

(defn- native-handler? [view]
  (contains? @w/actions view))

(defn- native-target? [view]
  (not (ffi/null? (u/objc-msg-send-0 view (u/sel "target")))))

(defn- native-action? [view]
  (not (ffi/null? (u/objc-msg-send-0 view (u/sel "action")))))

(defn- control-title [view]
  (u/nsstring->str (u/objc-msg-send-0 view (u/sel "title"))))

(defn- native-flags [view]
  {:enabled (control-enabled? view)
   :handler (native-handler? view)
   :target (native-target? view)
   :action (native-action? view)})

(defn- menu-observation [kind]
  (let [item (main/native-menu-item "Uninstall…")]
    {:kind kind
     :menu-item? (some? item)
     :menu-enabled (and item (control-enabled? item))
     :menu-handler (and item (native-handler? item))
     :menu-target (and item (native-target? item))
     :menu-action (and item (native-action? item))}))

(defn- occurrence [day start end label active?]
  {:day day
   :start start
   :end end
   :frozen-from 0
   :frozen? true
   :active? active?
   :today? true
   :label label})

(defn- complete-status
  ([state schedule]
   (complete-status state schedule nil))
  ([state schedule occurrences]
   (let [locked? (contains? #{:frozen :active} state)
         occurrences (vec (or occurrences []))
         deadline (when locked? (apply max (map :end occurrences)))]
     {:ok true
      :state state
      :now 1000000
      :zone "UTC"
      :zone-pending nil
      :clock-suspect false
      :safety-stopped false
      :sleep-refused false
      :unfrozen-at deadline
      :unfrozen-label (when locked? "Thu 06:15")
      :schedule schedule
      :occurrences occurrences
      :version "0.1.0"})))

(defn- blank-state []
  {:status nil
   :form nil
   :form-dirty? false
   :form-revision 0
   :schedule-generation 0
   :schedule-action-seq 0
   :uninstall-generation 0
   :uninstall-token-seq 0
   :uninstall-request nil
   :panel nil
   :error nil})

(defn- state-for [status]
  (:state (ui/handle-event (blank-state) [:status status])))

(defn- form-parts [window]
  (let [root (root-stack window)
        root-children (w/stack-children root)
        form-stack (first root-children)
        form-children (when form-stack (w/stack-children form-stack))
        rows-stack (first form-children)
        action-stack (second form-children)
        rows (when rows-stack (w/stack-children rows-stack))
        actions (when action-stack (w/stack-children action-stack))]
    {:root-children root-children
     :form-stack form-stack
     :form-children form-children
     :rows rows
     :actions actions}))

(defn- entry-views [window]
  (mapv (fn [row]
          (let [children (w/stack-children row)]
            [(nth children 2) (nth children 3)]))
        (:rows (form-parts window))))

(defn- form-observation [window kind retained-entries]
  (let [{:keys [root-children form-children rows actions]}
        (form-parts window)
        row-children (mapv w/stack-children rows)]
    {:kind kind
     :root-child-count (count root-children)
     :form-child-count (count form-children)
     :row-child-counts (mapv count row-children)
     :times (mapv (fn [children]
                    [(u/control-string (nth children 2))
                     (u/control-string (nth children 3))])
                  row-children)
     :entry-constraint-counts
     (mapv (fn [children]
             [(constraint-count (nth children 2))
              (constraint-count (nth children 3))])
           row-children)
     :switch-flags
     (mapv #(native-flags (nth % 1)) row-children)
     :entry-flags
     (mapv (fn [children]
             [(native-flags (nth children 2))
              (native-flags (nth children 3))])
           row-children)
     :action-labels (mapv control-title actions)
     :action-flags (mapv native-flags actions)
     :form-text (mapv u/control-string (drop 2 form-children))
     :same-entry-views
     (or (nil? @retained-entries)
         (= @retained-entries (entry-views window)))}))

(defn- growth-panel-observation [window retained-entries]
  (let [{:keys [root-children]} (form-parts window)
        panel-stack (second root-children)
        panel-content (first (w/stack-children panel-stack))
        content-children (w/stack-children panel-content)
        changes (w/stack-children (first content-children))
        message (second content-children)
        buttons (w/stack-children (nth content-children 2))]
    {:kind :growth-confirmation
     :root-child-count (count root-children)
     :same-entry-views (= @retained-entries (entry-views window))
     :changes (mapv u/control-string changes)
     :message (u/control-string message)
     :button-labels (mapv control-title buttons)
     :button-flags (mapv native-flags buttons)}))

(defn- uninstall-panel-observation [window kind]
  (let [root-children (w/stack-children (root-stack window))
        panel-stack (second root-children)
        panel-content (first (w/stack-children panel-stack))
        content-children (w/stack-children panel-content)
        button-node (second content-children)
        buttons (if (contains? #{:uninstall :stale-down-uninstall} kind)
                  (w/stack-children button-node)
                  [button-node])]
    {:kind kind
     :root-child-count (count root-children)
     :message (u/control-string (first content-children))
     :button-count (count buttons)
     :button-labels (mapv control-title buttons)
     :button-flags (mapv native-flags buttons)}))

(def ^:private enabled-flags
  {:enabled true :handler true :target true :action true})

(defn- actionable? [flags]
  (= enabled-flags flags))

(defn- inert? [{:keys [enabled handler target]}]
  (and (false? enabled) (false? handler) (false? target)))

(defn- enabled-pattern [flags]
  (mapv (fn [x]
          (if (map? x)
            (:enabled x)
            (enabled-pattern x)))
        flags))

(defn- actionability-pattern?
  [flags expected]
  (every? true?
          (map (fn [actual should-be-actionable?]
                 (if should-be-actionable?
                   (actionable? actual)
                   (inert? actual)))
               flags expected)))

(defn- form-layout-valid?
  [{:keys [root-child-count form-child-count row-child-counts
           entry-constraint-counts same-entry-views]}]
  (and (= 2 root-child-count)
       (<= 3 form-child-count)
       (= 7 (count row-child-counts))
       (every? #(= 5 %) row-child-counts)
       (= 14 (count (flatten entry-constraint-counts)))
       (every? pos? (flatten entry-constraint-counts))
       same-entry-views))

(defn- text-present? [observation expected]
  (some #{expected} (:form-text observation)))

(defn- action-flags [observation]
  (zipmap (:action-labels observation) (:action-flags observation)))

(defn- menu-disabled? [by-kind kind]
  (= {:menu-item? true
      :menu-enabled false
      :menu-handler false
      :menu-target false
      :menu-action false}
     (dissoc (get by-kind kind) :kind :run-loop-results)))

(defn- menu-enabled? [by-kind kind]
  (= {:menu-item? true
      :menu-enabled true
      :menu-handler true
      :menu-target true
      :menu-action true}
     (dissoc (get by-kind kind) :kind :run-loop-results)))

(defn -main [& _]
  (let [baseline (assoc sched/empty-schedule
                        :wed {:start "23:00" :end "07:00"}
                        :thu {:start "22:15" :end "06:15"})
        frozen-occurrences
        [(occurrence :wed 2000000 4000000 "Wed 23:00–07:00" false)
         (occurrence :thu 3000000 5000000 "Thu 22:15–06:15" false)]
        open-status (complete-status :open baseline)
        frozen-status (complete-status :frozen baseline frozen-occurrences)
        reordered-frozen-status
        (assoc frozen-status :occurrences (vec (reverse frozen-occurrences)))
        active-status
        (assoc reordered-frozen-status
               :state :active
               :occurrences
               (mapv #(if (= :wed (:day %)) (assoc % :active? true) %)
                     (:occurrences reordered-frozen-status)))
        changed-authority-status
        (let [occurrences
              (mapv #(if (= :thu (:day %))
                       (assoc % :end 5100000 :label "Thu 22:15–06:20")
                       %)
                    (:occurrences active-status))]
          (assoc active-status
                 :occurrences occurrences
                 :unfrozen-at 5100000
                 :unfrozen-label "Thu 06:20"))
        malformed-status (dissoc open-status :version)
        state (atom (state-for open-status))
        observations (atom [])
        evidence (atom {})
        retained-entries (atom nil)
        confirm-cycles 12]
    ;; The status item uses main/state while the window under test uses the
    ;; local atom above. Neither state starts a poller in this standalone main.
    (reset! main/state (blank-state))
    (g/set-dispatch! (fn [_ _] nil))
    (app/run
     (fn [window]
       (appkit/mount! window ui/view state)
       (main/create-status-item!)
       (add-watch main/state ::native-menu
                  (fn [_ _ _ _] (main/schedule-status-item-refresh!)))
       (swap! observations conj (menu-observation :menu-startup))
       (letfn [(settled! [f]
                 (app/schedule! #(app/schedule! f)))
               (set-event! [event]
                 (let [result (ui/handle-event @state event)]
                   (reset! state (:state result))
                   result))
               (observe-form! [kind next-step]
                 (clear-first-responder! window)
                 (settled!
                  #(do
                     (swap! observations conj
                            (form-observation window kind retained-entries))
                     (next-step))))
               (menu-transition! [kind next-status next-step]
                 (main/transition! main/state [:status next-status])
                 (settled!
                  #(do
                     (swap! observations conj (menu-observation kind))
                     (next-step))))
               (tracking-menu-transition! [kind next-status next-step]
                 (main/transition! main/state [:status next-status])
                 (let [run-results
                       (loop [results [] attempts 0]
                         (if (or (not (control-enabled?
                                      (main/native-menu-item "Uninstall…")))
                                 (= attempts 20))
                           results
                           (recur
                            (conj results
                                  (u/cf-run-loop-run-in-mode
                                   main/event-tracking-mode 0.05 1))
                            (inc attempts))))]
                   (swap! observations conj
                          (assoc (menu-observation kind)
                                 :run-loop-results run-results)))
                 (settled! next-step))
               (start-menu-sequence! []
                 (menu-transition!
                  :menu-down :daemon-down
                  #(menu-transition!
                    :menu-malformed malformed-status
                    #(menu-transition!
                      :menu-open open-status
                      #(tracking-menu-transition!
                        :menu-tracking-frozen frozen-status
                        #(menu-transition!
                          :menu-active active-status
                          #(menu-transition!
                            :menu-reopened open-status
                            start-form-sequence!)))))))
               (start-form-sequence! []
                 (observe-form!
                  :open
                  #(do
                     (set-event! [:status frozen-status])
                     (observe-form! :frozen-pristine make-invalid-draft!))))
               (make-invalid-draft! []
                 (set-event! [:edit-start :wed "23:30"])
                 (observe-form!
                  :invalid-growth
                  #(do
                     (set-event! [:revert])
                     (observe-form! :returned-to-baseline make-valid-draft!))))
               (make-valid-draft! []
                 (doseq [event [[:toggle :tue true]
                                [:edit-start :wed "22:30"]
                                [:edit-end :thu "08:30"]]]
                   (set-event! event))
                 (observe-form!
                  :valid-growth
                  #(do
                     (reset! retained-entries (entry-views window))
                     (swap! evidence assoc
                            :valid-generation (:schedule-generation @state)
                            :candidate (ui/form->schedule (:form @state)))
                     (set-event! [:status reordered-frozen-status])
                     (observe-form! :frozen-reordered enter-active!))))
               (enter-active! []
                 (swap! evidence assoc
                        :reordered-generation (:schedule-generation @state)
                        :reordered-dirty (:form-dirty? @state))
                 (set-event! [:status active-status])
                 (swap! evidence assoc
                        :active-generation (:schedule-generation @state)
                        :active-dirty (:form-dirty? @state)
                        :active-candidate (ui/form->schedule (:form @state)))
                 (observe-form!
                  :active-same-authority
                  #(show-growth-confirmation! 1)))
               (show-growth-confirmation! [cycle]
                 (let [saved (ui/handle-event @state [:save])
                       candidate (get-in saved [:request :schedule])
                       token {:baseline baseline
                              :candidate candidate
                              :zone "UTC"
                              :authority-frozen
                              (ui/canonical-frozen-signatures active-status)
                              :authority-unfrozen-at
                              (:unfrozen-at active-status)
                              :confirm-until-at 6000000
                              :activates-now true}
                       context (merge {:request (:request saved)}
                                      (:request-context saved))
                       response {:ok true
                                 :applied false
                                 :edit-mode :growth-only
                                 :confirm-growth token
                                 :confirm-until-label "Thu 08:30"
                                 :activates-now true}
                       panel-state
                       (:state
                        (ui/handle-event
                         (:state saved)
                         [:daemon-response response context]))]
                   (reset! state panel-state)
                   (settled!
                    #(do
                       (swap! observations conj
                              (assoc (growth-panel-observation
                                      window retained-entries)
                                     :cycle cycle))
                       (let [cancelled (ui/handle-event @state [:cancel-panel])]
                         (reset! state (:state cancelled))
                         (swap! evidence assoc
                                :cancel-request (:request cancelled)
                                :cancel-dirty (:form-dirty? @state)
                                :cancel-candidate
                                (ui/form->schedule (:form @state))
                                :confirm-token token))
                       (observe-form!
                        [:cancel cycle]
                        #(if (< cycle confirm-cycles)
                           (show-growth-confirmation! (inc cycle))
                           (change-authority!)))))))
               (change-authority! []
                 (let [before (:schedule-generation @state)]
                   (set-event! [:status changed-authority-status])
                   (swap! evidence assoc
                          :authority-before before
                          :authority-after (:schedule-generation @state)
                          :authority-dirty (:form-dirty? @state)
                          :authority-candidate
                          (ui/form->schedule (:form @state))))
                 (observe-form!
                  :canonical-authority-changed
                  #(do
                     (set-event! [:status open-status])
                     (observe-form! :reopened make-open-dirty!))))
               (make-open-dirty! []
                 (set-event! [:edit-start :wed "22:00"])
                 (swap! evidence assoc :open-dirty-before-down
                        (:form-dirty? @state))
                 (set-event! [:status :daemon-down])
                 (swap! evidence assoc
                        :down-dirty (:form-dirty? @state)
                        :down-candidate (ui/form->schedule (:form @state)))
                 (observe-form!
                  :daemon-down
                  #(do
                     (set-event! [:status malformed-status])
                     (observe-form!
                      :malformed
                      #(do
                         (set-event! [:status open-status])
                         (observe-form! :restored-open
                                        show-uninstall-panel!))))))
               (show-uninstall-panel! []
                 (clear-first-responder! window)
                 (let [open-panel (:state
                                   (ui/handle-event @state
                                                    [:open-uninstall]))]
                   (reset! state open-panel)
                   (settled!
                    #(do
                       (swap! observations conj
                              (uninstall-panel-observation window :uninstall))
                       (swap! state assoc :status :daemon-down)
                       (settled!
                        #(do
                           (swap! observations conj
                                  (uninstall-panel-observation
                                   window :stale-down-uninstall))
                           (reset! state
                                   (:state
                                    (ui/handle-event
                                     open-panel [:status frozen-status])))
                           (settled! show-frozen-uninstall-refusal!)))))))
               (show-frozen-uninstall-refusal! []
                 (swap! observations conj
                        (uninstall-panel-observation
                         window :frozen-uninstall-refusal))
                 (set-event! [:status active-status])
                 (settled!
                  #(do
                     (swap! observations conj
                            (uninstall-panel-observation
                             window :active-uninstall-refusal))
                     (set-event! [:status open-status])
                     (settled!
                      #(do
                         (swap! observations conj
                                (form-observation
                                 window :uninstall-reopened retained-entries))
                         (show-down-uninstall-refusal!))))))
               (show-down-uninstall-refusal! []
                 (set-event! [:open-uninstall])
                 (set-event! [:status :daemon-down])
                 (settled!
                  #(do
                     (swap! observations conj
                            (uninstall-panel-observation
                             window :down-uninstall-refusal))
                     (app/schedule! app/quit!))))]
         (app/schedule! start-menu-sequence!)))
     :title "GoToSleep AppKit behavior smoke"
     :width 520
     :height 390
     :auto-quit-ms 12000)
    (doseq [observed @observations]
      (println (pr-str observed)))
    (let [cancel-observations
          (filterv #(vector? (:kind %)) @observations)
          confirmation-observations
          (filterv #(= :growth-confirmation (:kind %)) @observations)
          by-kind
          (into {} (map (juxt :kind identity)
                        (remove #(or (vector? (:kind %))
                                     (= :growth-confirmation (:kind %)))
                                @observations)))
          open (:open by-kind)
          pristine (:frozen-pristine by-kind)
          invalid (:invalid-growth by-kind)
          returned (:returned-to-baseline by-kind)
          valid (:valid-growth by-kind)
          reordered (:frozen-reordered by-kind)
          active (:active-same-authority by-kind)
          changed (:canonical-authority-changed by-kind)
          reopened (:reopened by-kind)
          down (:daemon-down by-kind)
          malformed (:malformed by-kind)
          restored (:restored-open by-kind)
          uninstall-panel (:uninstall by-kind)
          stale-panel (:stale-down-uninstall by-kind)
          frozen-refusal (:frozen-uninstall-refusal by-kind)
          active-refusal (:active-uninstall-refusal by-kind)
          down-refusal (:down-uninstall-refusal by-kind)
          open-actions (action-flags open)
          pristine-actions (action-flags pristine)
          invalid-actions (action-flags invalid)
          returned-actions (action-flags returned)
          valid-actions (action-flags valid)]
      (when-not
       (and
        (every? form-layout-valid?
                (concat
                 [open pristine invalid returned valid reordered active changed
                  reopened down malformed restored
                  (:uninstall-reopened by-kind)]
                 cancel-observations))
        (= confirm-cycles (count cancel-observations))
        (= confirm-cycles (count confirmation-observations))
        (every? :same-entry-views confirmation-observations)

        (actionability-pattern? (:switch-flags open)
                                [true true true true true true true])
        (= [[false false] [false false] [true true] [true true]
            [false false] [false false] [false false]]
           (enabled-pattern (:entry-flags open)))
        (actionable? (get open-actions "Copy Monday to all days"))
        (inert? (get open-actions "Revert"))
        (inert? (get open-actions "Save"))

        (actionability-pattern? (:switch-flags pristine)
                                [true true false false true true true])
        (= (enabled-pattern (:entry-flags open))
           (enabled-pattern (:entry-flags pristine)))
        (inert? (get pristine-actions "Copy Monday to all days"))
        (inert? (get pristine-actions "Revert"))
        (inert? (get pristine-actions "Save extensions"))
        (text-present? pristine growth-banner)
        (text-present? pristine growth-noop)

        (inert? (get invalid-actions "Copy Monday to all days"))
        (actionable? (get invalid-actions "Revert"))
        (inert? (get invalid-actions "Save extensions"))
        (text-present? invalid growth-banner)
        (text-present? invalid growth-invalid)

        (every? inert? (vals returned-actions))
        (text-present? returned growth-banner)
        (text-present? returned growth-noop)

        (inert? (get valid-actions "Copy Monday to all days"))
        (actionable? (get valid-actions "Revert"))
        (actionable? (get valid-actions "Save extensions"))
        (actionability-pattern? (:switch-flags valid)
                                [true true false false true true true])
        (= [[false false] [true true] [true true] [true true]
            [false false] [false false] [false false]]
           (enabled-pattern (:entry-flags valid)))
        (text-present? valid growth-banner)
        (not (text-present? valid growth-invalid))
        (= (:times valid) (:times reordered) (:times active))
        (= (:action-flags valid)
           (:action-flags reordered)
           (:action-flags active))

        (= (:valid-generation @evidence)
           (:reordered-generation @evidence)
           (:active-generation @evidence))
        (true? (:reordered-dirty @evidence))
        (true? (:active-dirty @evidence))
        (= (:candidate @evidence) (:active-candidate @evidence))
        (< (:authority-before @evidence) (:authority-after @evidence))
        (false? (:authority-dirty @evidence))
        (= baseline (:authority-candidate @evidence))
        (text-present?
         changed
         (str "Schedule is frozen until Thu 06:20. "
              "Blocks may be added or made longer; shrinking or moving waits until afterward."))

        (every?
         #(and (= ["Tuesday: Off → 23:00–07:00"
                   "Wednesday: 23:00–07:00 → 22:30–07:00"
                   "Thursday: 22:15–06:15 → 22:15–08:30"]
                  (:changes %))
               (= immediate-lock-message (:message %))
               (= ["Confirm extensions" "Cancel"] (:button-labels %))
               (every? actionable? (:button-flags %)))
         confirmation-observations)
        (nil? (:cancel-request @evidence))
        (true? (:cancel-dirty @evidence))
        (= (:candidate @evidence) (:cancel-candidate @evidence))
        (= 6000000 (get-in @evidence [:confirm-token :confirm-until-at]))
        (true? (get-in @evidence [:confirm-token :activates-now]))

        (= (:switch-flags open) (:switch-flags reopened))
        (= (:entry-flags open) (:entry-flags reopened))
        (let [actions (action-flags reopened)]
          (and (actionable? (get actions "Copy Monday to all days"))
               (inert? (get actions "Revert"))
               (inert? (get actions "Save"))))
        (true? (:open-dirty-before-down @evidence))
        (false? (:down-dirty @evidence))
        (= baseline (:down-candidate @evidence))
        (every? inert? (:switch-flags down))
        (every? false? (flatten (enabled-pattern (:entry-flags down))))
        (every? inert? (:action-flags down))
        (every? inert? (:switch-flags malformed))
        (every? false? (flatten (enabled-pattern (:entry-flags malformed))))
        (every? inert? (:action-flags malformed))
        (= (:switch-flags open) (:switch-flags restored))
        (= (:entry-flags open) (:entry-flags restored))

        (menu-disabled? by-kind :menu-startup)
        (menu-disabled? by-kind :menu-down)
        (menu-disabled? by-kind :menu-malformed)
        (menu-enabled? by-kind :menu-open)
        (menu-disabled? by-kind :menu-tracking-frozen)
        (menu-disabled? by-kind :menu-active)
        (menu-enabled? by-kind :menu-reopened)
        (= ["Uninstall" "Cancel"] (:button-labels uninstall-panel))
        (every? actionable? (:button-flags uninstall-panel))
        (inert? (first (:button-flags stale-panel)))
        (actionable? (second (:button-flags stale-panel)))
        (= "Go To Sleep cannot be uninstalled until Thu 06:15."
           (:message frozen-refusal))
        (= ["OK"] (:button-labels frozen-refusal))
        (every? actionable? (:button-flags frozen-refusal))
        (= "Go To Sleep cannot be uninstalled until Thu 06:15."
           (:message active-refusal))
        (every? actionable? (:button-flags active-refusal))
        (= "Daemon not responding." (:message down-refusal))
        (every? actionable? (:button-flags down-refusal)))
        (println "FAIL: AppKit growth-only/authority/confirmation matrix")
        (System/exit 1)))
    (println "NON-ENFORCING APPKIT BEHAVIOR SMOKE OK")))
