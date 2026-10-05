(ns gotosleep.agent.ui
  "The agent's pure presentation layer: app-state -> menu title, menu items,
  settings-window hiccup, event reducers, warning decisions, and relock
  decisions. No AppKit here; the shell renders the hiccup and runs the effects.
  The agent holds no zone logic — the daemon formats every label, and
  occurrences carry :today?.

  App state:
    {:status  <last :status response> | :daemon-down | nil
     :baseline-schedule <latest authoritative schedule>
     :form    {<day> {:enabled? bool :start \"HH:MM\" :end \"HH:MM\"}}
     :panel   nil | {:kind :confirm-freeze :message s :freezes-now [...]}
                  | {:kind :confirm-growth :message s :confirm-growth {...}}
                  | {:kind :uninstall} | {:kind :uninstall-refused :message s}
     :error   nil | string
     :form-dirty? bool
     :form-revision integer
     :schedule-generation integer
     :schedule-action-seq integer
     :uninstall-generation integer
     :uninstall-token-seq integer
     :uninstall-request {:uninstall-generation integer
                         :uninstall-token integer} | nil
     :notified #{start-ms}}"
  (:require [gotosleep.schedule :as sched]))

(def moon "☾")
(def default-slot {:start "23:00" :end "07:00"})
(def ^:private daemon-not-responding "Daemon not responding.")
(def ^:private status-states #{:open :frozen :active})
(def ^:private schedule-actions
  #{:toggle :edit-start :edit-end :copy-monday :revert :save
    :confirm-freeze :confirm-growth})
(def ^:private growth-noop-message
  "Add a block or make an existing block longer to save.")
(def ^:private growth-invalid-message
  "While frozen, existing blocks must keep the same or an earlier start and the same or a later end.")

(defn- complete-occurrence?
  [occ]
  (and (map? occ)
       (contains? (set sched/days) (:day occ))
       (integer? (:start occ))
       (integer? (:end occ))
       (integer? (:frozen-from occ))
       (boolean? (:frozen? occ))
       (boolean? (:active? occ))
       (boolean? (:today? occ))
       (string? (:label occ))))

(defn canonical-frozen-signatures
  "The authority fields used by schedule generations, independent of daemon
  occurrence-vector order."
  [status]
  (->> (:occurrences status)
       (filter :frozen?)
       (map #(select-keys % [:day :start :end]))
       (sort-by (juxt :start :end :day))
       vec))

(defn successful-status?
  "Whether `status` has the complete successful daemon status shape the
  settings editor relies on. Anything else is treated as untrusted and keeps
  schedule editing disabled."
  [status]
  (when (and (map? status)
             (true? (:ok status))
             (contains? status-states (:state status))
             (integer? (:now status))
             (string? (:zone status))
             (or (nil? (:zone-pending status)) (string? (:zone-pending status)))
             (boolean? (:clock-suspect status))
             (boolean? (:safety-stopped status))
             (boolean? (:sleep-refused status))
             (or (nil? (:unfrozen-at status)) (integer? (:unfrozen-at status)))
             (or (nil? (:unfrozen-label status)) (string? (:unfrozen-label status)))
             (nil? (sched/validate (:schedule status)))
             (vector? (:occurrences status))
             (every? complete-occurrence? (:occurrences status))
             (string? (:version status)))
    (let [frozen (filterv :frozen? (:occurrences status))
          active (filterv :active? (:occurrences status))
          locked? (contains? #{:frozen :active} (:state status))
          flags-consistent?
          (case (:state status)
            :open (and (empty? frozen) (empty? active))
            :frozen (and (seq frozen) (empty? active))
            :active (and (seq active)
                         (every? :frozen? active)))
          deadline (when (seq frozen) (apply max (map :end frozen)))]
      (boolean
       (and flags-consistent?
            (if locked?
              (and (integer? (:unfrozen-at status))
                   (string? (:unfrozen-label status))
                   (= deadline (:unfrozen-at status)))
              (and (nil? (:unfrozen-at status))
                   (nil? (:unfrozen-label status)))))))))

(defn edit-mode
  "The Settings authority mode derived only from a complete status."
  [{:keys [status]}]
  (if-not (successful-status? status)
    :read-only
    (if (= :open (:state status)) :open :growth-only)))

(defn schedule-editable?
  "Whether Settings may accept some schedule edits."
  [state]
  (contains? #{:open :growth-only} (edit-mode state)))

(defn uninstall-available?
  "Whether the latest agent-known authority permits offering uninstall.
  This is deliberately the same complete-open test used by every uninstall
  menu, panel, reducer, and request guard."
  [{:keys [status]}]
  (boolean
   (and (successful-status? status)
        (= :open (:state status)))))

(defn uninstall-request-current?
  "Whether an uninstall request context still names the one-shot request
  issued in the current complete-open availability generation. The shell uses
  this predicate before claiming queued work for network I/O."
  [state request-context]
  (let [context (select-keys request-context
                             [:uninstall-generation :uninstall-token])]
    (and (uninstall-available? state)
         (= context (:uninstall-request state))
         (integer? (:uninstall-generation context))
         (integer? (:uninstall-token context)))))

(defn- locked-status?
  [status]
  (and (successful-status? status)
       (contains? #{:frozen :active} (:state status))))

(defn- frozen-message
  [state]
  (when (= :growth-only (edit-mode state))
    (str "Schedule is frozen until " (get-in state [:status :unfrozen-label])
         ". Blocks may be added or made longer; shrinking or moving waits until afterward.")))

;; --- reading occurrences ---------------------------------------------------

(defn parse-label
  "\"Mon 23:00–07:00\" -> {:day \"Mon\" :start \"23:00\" :end \"07:00\"}."
  [label]
  (when-let [[_ day s e] (re-matches #"(\w+) (\d\d:\d\d)–(\d\d:\d\d)" (or label ""))]
    {:day day :start s :end e}))

(defn- active-occ [status] (first (filter :active? (:occurrences status))))
(defn- next-occ [status]
  (->> (:occurrences status)
       (remove :active?)
       (filter #(> (:start %) (:now status)))
       (sort-by :start)
       first))

(defn- mmss [ms]
  (let [s (quot (max 0 ms) 1000)] (format "%d:%02d" (quot s 60) (mod s 60))))

;; --- menu title ------------------------------------------------------------

(defn menu-title
  "The status-item title; the first matching row wins."
  [{:keys [status]}]
  (cond
    (not (successful-status? status)) (str moon " ⚠")
    :else
    (let [now (:now status)
          ao (active-occ status)
          no (next-occ status)
          soon? (and no (< 0 (- (:start no) now) (inc 300000)))]
      (cond
        ao (str moon " until " (:end (parse-label (:label ao))))
        soon? (str moon " in " (mmss (- (:start no) now)))
        (= :frozen (:state status))
        (let [fo (first (filter :frozen? (:occurrences status)))]
          (str moon " " (:start (parse-label (:label fo))) " 🔒"))
        no (str moon " " (when-not (:today? no) (str (:day (parse-label (:label no))) " "))
                (:start (parse-label (:label no))))
        :else moon))))

;; --- menu items ------------------------------------------------------------

(defn menu-items
  "The status menu: disabled status lines, then Settings… and Uninstall…."
  [{:keys [status] :as state}]
  (let [down? (not (successful-status? status))
        uninstall? (uninstall-available? state)
        lines
        (if down?
          [{:label "Daemon not responding" :enabled? false}]
          (concat
           (when-let [o (or (active-occ status) (next-occ status))]
             [{:label (:label o) :enabled? false}])
           (when (and (= :frozen (:state status)) (:unfrozen-label status))
             [{:label (str "Frozen until " (:unfrozen-label status)) :enabled? false}])
           (when (:zone-pending status)
             [{:label (str "Time zone change pending: " (:zone-pending status)) :enabled? false}])
           (when (:clock-suspect status)
             [{:label "Clock unverified" :enabled? false}])
           (when (:sleep-refused status)
             [{:label "Couldn't put the Mac to sleep" :enabled? false}])))]
    (vec (concat lines
                 [{:label "Settings…" :enabled? true :action [:open-settings]}
                  (cond-> {:label "Uninstall…" :enabled? uninstall?}
                    uninstall? (assoc :action [:open-uninstall]))]))))

;; --- form <-> schedule ------------------------------------------------------

(defn schedule->form [schedule]
  (into {} (for [d sched/days]
             [d (if-let [s (get schedule d)]
                  {:enabled? true :start (:start s) :end (:end s)}
                  (assoc default-slot :enabled? false))])))

(defn form->schedule [form]
  (into {} (for [d sched/days]
             [d (let [{:keys [enabled? start end]} (get form d)]
                  (when enabled? {:start start :end end}))])))

(defn authoritative-schedule
  "The explicit baseline used for dirty state and growth controls."
  [state]
  (or (:baseline-schedule state)
      (when (successful-status? (:status state))
        (get-in state [:status :schedule]))
      sched/empty-schedule))

(defn semantic-dirty?
  [state]
  (not= (form->schedule (:form state))
        (authoritative-schedule state)))

(defn- unwrapped-end
  [{:keys [start end]}]
  (let [s (sched/parse-hhmm start)
        e (sched/parse-hhmm end)]
    (when (and s e)
      (if (> e s) e (+ e 1440)))))

(defn- contains-slot?
  [candidate baseline]
  (let [candidate-start (sched/parse-hhmm (:start candidate))
        baseline-start (sched/parse-hhmm (:start baseline))
        candidate-end (unwrapped-end candidate)
        baseline-end (unwrapped-end baseline)]
    (and candidate-start baseline-start candidate-end baseline-end
         (<= candidate-start baseline-start)
         (>= candidate-end baseline-end))))

(defn growth-candidate?
  "Local wall-clock preflight for a growth-only edit. The daemon still owns
  DST instant containment and the complete occurrence-window check."
  [baseline candidate]
  (and (nil? (sched/validate candidate))
       (not= baseline candidate)
       (every?
        (fn [d]
          (let [before (get baseline d)
                after (get candidate d)]
            (or (= before after)
                (and (nil? before) (some? after))
                (and before after
                     (not= before after)
                     (contains-slot? after before)))))
        sched/days)))

(defn- save-enabled?
  [state]
  (let [mode (edit-mode state)
        dirty? (semantic-dirty? state)
        candidate (form->schedule (:form state))
        baseline (authoritative-schedule state)]
    (and dirty?
         (nil? (sched/validate candidate))
         (case mode
           :open true
           :growth-only (growth-candidate? baseline candidate)
           false))))

(defn- growth-draft-message
  [state]
  (when (= :growth-only (edit-mode state))
    (let [candidate (form->schedule (:form state))
          baseline (authoritative-schedule state)]
      (cond
        (= candidate baseline) growth-noop-message
        (some? (sched/validate candidate)) nil
        (not (growth-candidate? baseline candidate)) growth-invalid-message))))

;; --- settings view ----------------------------------------------------------

(defn- row [state form d]
  (let [{:keys [enabled? start end]} (get form d)
        status (:status state)
        mode (edit-mode state)
        baseline-enabled? (some? (get (authoritative-schedule state) d))
        switch-enabled? (case mode
                          :open true
                          :growth-only (not baseline-enabled?)
                          false)
        entries-enabled? (and (not= :read-only mode) enabled?)
        occ (first (filter #(= d (:day %)) (:occurrences status)))
        status-label (cond
                       (and occ (:active? occ)) "active now"
                       (and occ (:frozen? occ) (:unfrozen-label status))
                       (str "frozen until " (:unfrozen-label status))
                       :else "")]
    [:hbox {:spacing 8}
     [:label {:width-request 90 :label (sched/day-names d)}]
     [:switch (cond-> {:active enabled? :sensitive switch-enabled?}
                switch-enabled? (assoc :on {:toggled [[:toggle d]]}))]
     [:entry (cond-> {:width-request 70 :text start :sensitive entries-enabled?}
               entries-enabled? (assoc :on {:change [[:edit-start d]]}))]
     [:entry (cond-> {:width-request 70 :text end :sensitive entries-enabled?}
               entries-enabled? (assoc :on {:change [[:edit-end d]]}))]
     [:label {:width-request 160 :label status-label}]]))

(defn- current-uninstall-panel?
  [{:keys [panel uninstall-generation] :as state}]
  (and (= :uninstall (:kind panel))
       (uninstall-available? state)
       (integer? (:uninstall-token panel))
       (= (or uninstall-generation 0)
          (:uninstall-generation panel))))

(defn- panel-view [{:keys [panel] :as state}]
  (let [{:keys [kind message]} panel
        uninstall? (current-uninstall-panel? state)
        schedule-confirm? (and (= (:schedule-generation panel)
                                  (:schedule-generation state))
                               (= (:form-revision panel)
                                  (:form-revision state))
                               (= (:edit-mode panel) (edit-mode state))
                               (= (:schedule panel)
                                  (form->schedule (:form state)))
                               (or (not= :confirm-growth kind)
                                   (and (= (:baseline panel)
                                           (authoritative-schedule state))
                                        (= (:baseline
                                            (:confirm-growth panel))
                                           (authoritative-schedule state))
                                        (= (:candidate
                                            (:confirm-growth panel))
                                           (:schedule panel)))))]
  (case kind
    :confirm-freeze
    [:vbox {:spacing 8}
     [:label {:label message :wrap true}]
     [:hbox {:spacing 8}
      [:button (cond-> {:label "Confirm" :sensitive schedule-confirm?}
                 schedule-confirm? (assoc :on {:click [[:confirm-freeze]]}))]
      [:button {:label "Cancel" :on {:click [[:cancel-panel]]}}]]]
    :confirm-growth
    [:vbox {:spacing 8}
     (into [:vbox {:spacing 4}]
           (map (fn [{:keys [day before after]}]
                  [:label {:label (str (sched/day-names day) ": "
                                      (or before "Off") " → " (or after "Off"))}])
                (:changes panel)))
     [:label {:label message :wrap true}]
     [:hbox {:spacing 8}
      [:button (cond-> {:label "Confirm extensions"
                        :sensitive schedule-confirm?}
                 schedule-confirm?
                 (assoc :on {:click [[:confirm-growth]]}))]
      [:button {:label "Cancel" :on {:click [[:cancel-panel]]}}]]]
    :uninstall
    [:vbox {:spacing 8}
     [:label {:label "This removes Go To Sleep and your schedule."}]
     [:hbox {:spacing 8}
      [:button (cond-> {:label "Uninstall" :sensitive uninstall?}
                 uninstall? (assoc :on {:click [[:confirm-uninstall]]}))]
      [:button {:label "Cancel" :on {:click [[:cancel-panel]]}}]]]
    :uninstall-refused
    [:vbox {:spacing 8}
     [:label {:label message :wrap true}]
     [:button {:label "OK" :on {:click [[:cancel-panel]]}}]])))

(defn view
  "The settings-window hiccup for the current app state."
  [{:keys [status form panel error] :as state}]
  ;; Keep the complete form subtree mounted while a confirmation panel is
  ;; visible. glitter-uikit currently remembers width constraints by native
  ;; view address after unmount; AppKit may reuse those addresses, causing
  ;; reconstructed entries to lose their width and disappear. Toggling
  ;; visibility preserves the original controls and their constraints.
  (let [editable? (schedule-editable? state)
        form (or form (schedule->form sched/empty-schedule))
        state (assoc state :form form)
        mode (edit-mode state)
        dirty? (semantic-dirty? state)
        save? (save-enabled? state)
        locked-message (frozen-message state)
        draft-message (growth-draft-message state)
        copy? (= :open mode)
        revert? (and editable? dirty?)
        save-label (if (= :growth-only mode) "Save extensions" "Save")]
    [:vbox {:spacing 0}
     [:vbox {:spacing 8 :margin 12 :visible (nil? panel)}
      (into [:vbox {:spacing 4}] (map #(row state form %) sched/days))
      [:hbox {:spacing 8}
       [:button (cond-> {:label "Copy Monday to all days" :sensitive copy?}
                  copy? (assoc :on {:click [[:copy-monday]]}))]
       [:button (cond-> {:label "Revert" :sensitive revert?}
                  revert? (assoc :on {:click [[:revert]]}))]
       [:button (cond-> {:label save-label :sensitive save?}
                  save? (assoc :on {:click [[:save]]}))]]
      ;; Keep the banner mounted so open/frozen/active transitions do not
      ;; reconstruct or reorder the schedule controls.
      [:label {:label (or locked-message "") :wrap true
               :visible (some? locked-message)}]
      [:label {:label (or draft-message "") :wrap true
               :visible (some? draft-message)}]
     (when error [:label {:label error :wrap true}])]
     [:vbox {:spacing 8 :margin 12 :visible (some? panel)}
      (when panel (panel-view state))]]))

;; --- event reducers ---------------------------------------------------------

(defn event-actions
  "The actions to reduce for one glitter event. glitter-core calls
  `(*dispatch* event-map handler-data)`, where handler-data is the widget's
  :on value, e.g. [[:toggle :mon]]. The event's value (a switch's boolean, an
  entry's text) is at [:glitter/dom-event :glitter/value] and is appended to
  each action, so [:toggle :mon] becomes [:toggle :mon true]. Clicks carry no
  value and pass through unchanged."
  [event handler-data]
  (let [v (get-in event [:glitter/dom-event :glitter/value])]
    (->> (if (sequential? handler-data) handler-data [])
         (filter vector?)
         (mapv #(if (some? v) (conj % v) %)))))

(def window-actions
  "Actions after which the shell should show the settings window."
  #{:open-settings :open-uninstall})

(defn- ensure-form [state]
  (if (:form state)
    state
    (assoc state
           :baseline-schedule (authoritative-schedule state)
           :form (schedule->form (authoritative-schedule state))
           :form-dirty? false
           :form-revision (or (:form-revision state) 0)
           :schedule-generation (or (:schedule-generation state) 0)
           :schedule-action-seq (or (:schedule-action-seq state) 0))))

(defn- change-form
  "Apply a user edit and advance the revision used to reject stale responses."
  [state f]
  (let [changed (f state)]
    (if (= (:form state) (:form changed))
      state
      (-> changed
          (assoc :form-dirty? (semantic-dirty? changed)
                 :panel nil
                 :error nil)
          (update :form-revision (fnil inc 0))))))

(defn schedule-request-current?
  "Whether a queued or returning schedule request still belongs to the exact
  authority/form/action chain that emitted it."
  [state request-context]
  (and (schedule-editable? state)
       (integer? (:generation request-context))
       (integer? (:revision request-context))
       (integer? (:action-token request-context))
       (= (:generation request-context) (:schedule-generation state))
       (= (:revision request-context) (:form-revision state))
       (= (:action-token request-context) (:schedule-action-seq state))
       (= (:edit-mode request-context) (edit-mode state))))

(defn- request-stale?
  [state request-context]
  (case (get-in request-context [:request :op])
    :set-schedule
    (not (schedule-request-current? state request-context))

    :uninstall
    (not (uninstall-request-current? state request-context))

    false))

(defn- clear-recovered-connectivity-error [state status]
  (if-not (successful-status? status)
    state
    (cond-> state
      (= daemon-not-responding (:error state))
      (assoc :error nil)

      (and (= :uninstall-refused (get-in state [:panel :kind]))
           (= daemon-not-responding (get-in state [:panel :message]))
           (= :open (:state status))
           (not (true? (get-in state [:panel :status-derived?]))))
      (assoc :panel nil))))

(defn- uninstall-refusal-panel
  [status generation]
  {:kind :uninstall-refused
   :message (if (locked-status? status)
              (str "Go To Sleep cannot be uninstalled until "
                   (:unfrozen-label status) ".")
              daemon-not-responding)
   :status-derived? true
   :uninstall-generation generation})

(defn- reconcile-uninstall-status
  [state status new-available? generation]
  (let [panel (:panel state)
        uninstall-panel? (= :uninstall (:kind panel))
        uninstall-refusal? (= :uninstall-refused (:kind panel))
        status-refusal? (and uninstall-refusal?
                             (true? (:status-derived? panel)))
        followed-panel? (or uninstall-panel? uninstall-refusal?)]
    (cond
      (and new-available? status-refusal?)
      (assoc state :panel nil)

      (and (not new-available?) followed-panel?)
      (assoc state :panel (uninstall-refusal-panel status generation))

      :else state)))

(defn- status-authority-key
  [status]
  (if (successful-status? status)
    [(if (= :open (:state status)) :open :growth-only)
     (:schedule status)
     (:zone status)
     (canonical-frozen-signatures status)]
    [:unavailable]))

(defn- schedule-panel?
  [panel]
  (contains? #{:confirm-freeze :confirm-growth} (:kind panel)))

(defn- reconcile-status
  [state status]
  (let [old-uninstall? (uninstall-available? state)
        new-uninstall? (and (successful-status? status)
                            (= :open (:state status)))
        uninstall-changed? (not= old-uninstall? new-uninstall?)
        uninstall-generation (+ (or (:uninstall-generation state) 0)
                                (if uninstall-changed? 1 0))
        old-key (or (:schedule-authority-key state)
                    (status-authority-key (:status state)))
        new-key (status-authority-key status)
        authority-changed? (not= old-key new-key)
        complete? (successful-status? status)
        s (cond-> (assoc state
                         :status status
                         :schedule-authority-key new-key
                         :uninstall-generation uninstall-generation)
            authority-changed?
            (-> (update :schedule-generation (fnil inc 0))
                (update :form-revision (fnil inc 0))
                (assoc :schedule-request nil))

            uninstall-changed?
            (assoc :uninstall-request nil))
        s (if (and authority-changed? (schedule-panel? (:panel s)))
            (assoc s :panel nil)
            s)
        s (if complete?
            (let [baseline (:schedule status)]
              (if (or authority-changed? (not (semantic-dirty? s)))
                (assoc s
                       :baseline-schedule baseline
                       :form (schedule->form baseline)
                       :form-dirty? false)
                (assoc s
                       :baseline-schedule baseline
                       :form-dirty? (semantic-dirty?
                                     (assoc s :baseline-schedule baseline)))))
            (let [baseline (authoritative-schedule s)]
              (assoc s
                     :form (schedule->form baseline)
                     :form-dirty? false)))
        s (clear-recovered-connectivity-error s status)]
    (reconcile-uninstall-status
     s status new-uninstall? uninstall-generation)))

(defn- next-schedule-context
  [state]
  (let [token (inc (or (:schedule-action-seq state) 0))
        state (assoc state :schedule-action-seq token)]
    [state {:generation (or (:schedule-generation state) 0)
            :revision (or (:form-revision state) 0)
            :action-token token
            :edit-mode (edit-mode state)}]))

(defn- schedule-request
  [state schedule & kvs]
  (apply assoc {:op :set-schedule
                :schedule schedule
                :edit-mode (edit-mode state)}
         kvs))

(defn- freeze-confirmation-signatures
  "The protocol confirmation token is deliberately narrower than the rich
  occurrence wire shape used for display. Returns nil for a malformed daemon
  response so the caller can fail closed instead of emitting a bad request."
  [occurrences]
  (when (vector? occurrences)
    (let [signatures
          (mapv (fn [occ]
                  (when (and (map? occ)
                             ((set sched/days) (:day occ))
                             (integer? (:start occ))
                             (integer? (:end occ)))
                    (select-keys occ [:day :start :end])))
                occurrences)]
      (when (every? some? signatures)
        signatures))))

(defn- panel-current?
  [state kind]
  (let [panel (:panel state)
        token (:confirm-growth panel)
        baseline (authoritative-schedule state)]
    (and (= kind (:kind panel))
         (= (:schedule-generation panel) (:schedule-generation state))
         (= (:form-revision panel) (:form-revision state))
         (= (:edit-mode panel) (edit-mode state))
         (= (:schedule panel) (form->schedule (:form state)))
         (case kind
           :confirm-growth
           (and (map? token)
                (= baseline (:baseline panel))
                (= baseline (:baseline token))
                (= (:schedule panel) (:candidate token)))

           :confirm-freeze
           (seq (freeze-confirmation-signatures (:freezes-now panel)))

           true))))

(defn- slot-label [slot]
  (when slot (str (:start slot) "–" (:end slot))))

(defn- changed-slot-lines
  [baseline candidate]
  (->> sched/days
       (keep (fn [day]
               (let [before (get baseline day)
                     after (get candidate day)]
                 (when (not= before after)
                   {:day day
                    :before (slot-label before)
                    :after (slot-label after)}))))
       vec))

(defn- growth-confirmation-message
  [confirm-until-label activates-now]
  (if activates-now
    (str "Confirming will lock the screen immediately and keep it locked until "
         confirm-until-label ".")
    (str "These changes cannot be undone until " confirm-until-label ".")))

(defn- install-growth-panel
  [state request response]
  (let [token (or (:confirm-growth response)
                  (get-in response [:error :confirm-growth]))
        label (or (:confirm-until-label response)
                  (get-in response [:error :confirm-until-label])
                  "the block ends")
        activates-now (boolean (or (:activates-now response)
                                   (get-in response [:error :activates-now])))
        baseline (or (:baseline token) (authoritative-schedule state))
        candidate (or (:candidate token) (:schedule request))
        token-key (when (map? token)
                    [:growth-only baseline (:zone token)
                     (vec (:authority-frozen token))])
        authority-changed? (and token-key
                                (not= token-key
                                      (or (:schedule-authority-key state)
                                          (status-authority-key (:status state)))))
        state (cond-> state
                authority-changed?
                (-> (assoc :schedule-authority-key token-key
                           :baseline-schedule baseline)
                    (update :schedule-generation (fnil inc 0))
                    (update :form-revision (fnil inc 0))))
        state (assoc state :form-dirty?
                     (semantic-dirty? (assoc state
                                             :baseline-schedule baseline)))
        panel {:kind :confirm-growth
               :message (or (get-in response [:error :message])
                            (growth-confirmation-message label activates-now))
               :confirm-growth token
               :confirm-until-label label
               :activates-now activates-now
               :changes (changed-slot-lines baseline candidate)
               :schedule candidate
               :baseline baseline
               :schedule-generation (or (:schedule-generation state) 0)
               :form-revision (or (:form-revision state) 0)
               :edit-mode :growth-only}]
    (assoc state :panel panel :error nil)))

(defn- edit-allowed?
  [state op args]
  (let [mode (edit-mode state)
        day (first args)
        enabled? (get-in state [:form day :enabled?])
        baseline? (some? (get (authoritative-schedule state) day))
        day? (contains? (set sched/days) day)]
    (case op
      :toggle (and day?
                   (boolean? (second args))
                   (not= :read-only mode)
                   (or (= :open mode) (not baseline?)))
      :edit-start (and day? (string? (second args))
                       (not= :read-only mode) enabled?)
      :edit-end (and day? (string? (second args))
                     (not= :read-only mode) enabled?)
      :copy-monday (= :open mode)
      :revert (and (not= :read-only mode) (semantic-dirty? state))
      :save (save-enabled? state)
      :confirm-freeze (panel-current? state :confirm-freeze)
      :confirm-growth (panel-current? state :confirm-growth)
      false)))

(defn handle-event
  "Reduce one UI event into the app state. Returns {:state s :request r|nil},
  where a non-nil request is what the shell should send to the daemon; its
  response comes back as a [:daemon-response …] event. Uninstall requests also
  return :request-context with the one-shot generation/token that the shell
  must carry unchanged through the queue and response. Pure."
  [state event]
  (let [[op & args] event
        ret (fn [s & [req request-context]]
              (cond-> {:state s :request req}
                request-context (assoc :request-context request-context)))]
    (if (or (and (contains? schedule-actions op)
                 (not (edit-allowed? (ensure-form state) op args)))
            (and (= :open-uninstall op)
                 (not (uninstall-available? state)))
            (and (= :confirm-uninstall op)
                 (not (current-uninstall-panel? state)))
            (and (= :cancel-panel op)
                 (not (contains? #{:confirm-freeze :confirm-growth :uninstall
                                   :uninstall-refused}
                                 (get-in state [:panel :kind])))))
      (ret state)
      (let [state (ensure-form state)]
        (case op
          :toggle       (ret (change-form state #(assoc-in % [:form (first args) :enabled?] (second args))))
          :edit-start   (ret (change-form state #(assoc-in % [:form (first args) :start] (second args))))
          :edit-end     (ret (change-form state #(assoc-in % [:form (first args) :end] (second args))))
          :copy-monday  (let [mon (get-in state [:form :mon])]
                          (ret (change-form
                                state
                                #(update % :form
                                         (fn [form]
                                           (into {} (for [[d v] form]
                                                      [d (merge v (select-keys mon [:enabled? :start :end]))])))))))
          :revert       (ret (-> state
                                 (assoc :form (schedule->form (authoritative-schedule state)))
                                 (assoc :form-dirty? false :error nil :panel nil)
                                 (update :form-revision (fnil inc 0))))
          :save         (let [[s context]
                              (next-schedule-context
                               (assoc state :error nil :panel nil))]
                          (ret s
                               (schedule-request s
                                                 (form->schedule (:form s))
                                                 :dry-run true)
                               context))
          :confirm-freeze
          (let [panel (:panel state)
                [s context] (next-schedule-context (assoc state :panel nil))]
            (ret s
                 (schedule-request s (:schedule panel)
                                   :confirm-freeze
                                   (freeze-confirmation-signatures
                                    (:freezes-now panel)))
                 context))
          :confirm-growth
          (let [panel (:panel state)
                [s context] (next-schedule-context (assoc state :panel nil))]
            (ret s
                 (schedule-request s (:schedule panel)
                                   :confirm-growth (:confirm-growth panel))
                 context))
          :cancel-panel
          (if (contains? #{:confirm-freeze :confirm-growth :uninstall
                           :uninstall-refused}
                         (get-in state [:panel :kind]))
            (ret (assoc state :panel nil :error nil))
            (ret state))

          :open-uninstall
          (let [generation (or (:uninstall-generation state) 0)
                token (inc (or (:uninstall-token-seq state) 0))]
            (ret (assoc state
                        :uninstall-token-seq token
                        :panel {:kind :uninstall
                                :uninstall-generation generation
                                :uninstall-token token})))

          :confirm-uninstall
          (if (current-uninstall-panel? state)
            (let [context (select-keys (:panel state)
                                       [:uninstall-generation :uninstall-token])]
              (ret (assoc state
                          :panel nil
                          :uninstall-request context)
                   {:op :uninstall}
                   context))
            (ret state))

          :open-settings (ret (assoc state :window-visible? true))

          :status
          (ret (reconcile-status state (second event)))

          :notification-posted
          (ret (update state :notified (fnil conj #{}) (first args)))

          :daemon-response
          (let [resp (second event)
                request-context (nth event 2 nil)
                request (:request request-context)
                schedule-request? (= :set-schedule (:op request))
                uninstall-request? (= :uninstall (:op request))
                dry-run? (and schedule-request? (true? (:dry-run request)))
                stale? (request-stale? state request-context)]
            (if stale?
              (ret state)
              (let [state (if uninstall-request?
                            (assoc state :uninstall-request nil)
                            state)]
                (cond
                (nil? resp)
                (if uninstall-request?
                  (ret (assoc state :panel {:kind :uninstall-refused
                                            :message daemon-not-responding
                                            :uninstall-generation
                                            (:uninstall-generation request-context)}
                              :error nil))
                  (let [s (if schedule-request?
                            (reconcile-status state :daemon-down)
                            state)]
                    (ret (assoc s :error daemon-not-responding :panel nil))))

                (:error resp)
                (let [err (:error resp)]
                  (cond
                    (and (= :confirm-required (:code err))
                         (:confirm-growth err))
                    (ret (install-growth-panel state request resp))

                    (= :confirm-required (:code err))
                    (if-let [signatures
                             (seq (freeze-confirmation-signatures
                                   (:freezes-now err)))]
                      (ret (assoc state :panel {:kind :confirm-freeze
                                                :message (:message err)
                                                :freezes-now (vec signatures)
                                                :schedule (:schedule request)
                                                :schedule-generation
                                                (:schedule-generation state)
                                                :form-revision
                                                (:form-revision state)
                                                :edit-mode :open}
                                  :error nil))
                      (ret (assoc (reconcile-status state :daemon-down)
                                  :error daemon-not-responding
                                  :panel nil)))

                    uninstall-request?
                    (ret (assoc state :panel {:kind :uninstall-refused
                                              :message (:message err)
                                              :uninstall-generation
                                              (:uninstall-generation request-context)}
                                :error nil))

                    :else
                    (ret (assoc state :error (:message err) :panel nil))))

                (and schedule-request? (:applied resp))
                (if (successful-status? (:status resp))
                  (let [s (reconcile-status state (:status resp))
                        baseline (get-in resp [:status :schedule])]
                  (ret (-> s
                             (assoc :baseline-schedule baseline
                                    :form (schedule->form baseline)
                                    :form-dirty? false
                                    :error nil
                                    :panel nil)
                             (update :form-revision (fnil inc 0)))))
                  (ret (assoc (reconcile-status state :daemon-down)
                              :error daemon-not-responding
                              :panel nil)))

                dry-run?
                (let [signatures
                      (freeze-confirmation-signatures (:freezes-now resp))]
                  (cond
                    (= :growth-only (:edit-mode resp))
                    (ret (install-growth-panel state request resp))

                    (nil? signatures)
                    (ret (assoc (reconcile-status state :daemon-down)
                                :error daemon-not-responding
                                :panel nil))

                    (seq signatures)
                    (ret (assoc state :error nil
                                :panel {:kind :confirm-freeze
                                        :message (str "This takes effect immediately and can't be undone until "
                                                      (or (:confirm-until-label resp) "the block ends") ".")
                                        :freezes-now signatures
                                        :schedule (:schedule request)
                                        :schedule-generation
                                        (:schedule-generation state)
                                        :form-revision (:form-revision state)
                                        :edit-mode :open}))

                    :else
                    (ret (assoc state :error nil :panel nil)
                         (dissoc request :dry-run))))

                :else
                (ret (assoc state :error nil :panel nil))))))

          (ret state))))))

;; --- warning ----------------------------------------------------------------

(defn notify-decision
  "Whether to post the 5-minute warning. Posts once per occurrence when the
  state is not active and the next block starts within (0, 300 s]. Returns
  {:post? bool :title s :body s :start ms}."
  [{:keys [status notified]}]
  (if-not (successful-status? status)
    {:post? false}
    (let [no (next-occ status)
          now (:now status)]
      (if (and (not= :active (:state status))
               no
               (< 0 (- (:start no) now) (inc 300000))
               (not (contains? (or notified #{}) (:start no))))
        (let [mins (long (Math/ceil (/ (- (:start no) now) 60000.0)))]
          {:post? true :title "Go To Sleep" :start (:start no)
           :body (str "Bedtime at " (:start (parse-label (:label no)))
                      " — the screen locks in " mins
                      (if (= 1 mins) " minute." " minutes."))})
        {:post? false}))))

(defn should-relock?
  "Whether the agent's secondary relock applies. The one-argument form answers
  only the active-state part; the full form also validates the current session
  and the monotonic one-second rate limit."
  ([{:keys [status]}]
   (and (successful-status? status)
        (= :active (:state status))
        (not (:safety-stopped status))))
  ([{:keys [last-relock-mono-ms] :as state} session mono-ms]
   (and (should-relock? state)
        (map? session)
        (true? (get session "kCGSSessionOnConsoleKey"))
        (or (not (contains? session "CGSSessionScreenIsLocked"))
            (false? (get session "CGSSessionScreenIsLocked")))
        (or (nil? last-relock-mono-ms)
            (>= (- mono-ms last-relock-mono-ms) 1000)))))
