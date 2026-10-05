(ns gotosleep.agent.ui-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [gotosleep.agent.ui :as ui]
            [gotosleep.protocol :as proto]
            [gotosleep.schedule :as sched]))

(def h (fn [hh mm] (+ (* hh 3600000) (* mm 60000))))

(defn daemon-status
  ([state] (daemon-status state sched/empty-schedule {}))
  ([state schedule] (daemon-status state schedule {}))
  ([state schedule overrides]
   (let [locked? (contains? #{:frozen :active} state)
         active? (= :active state)
         default-occ (when locked?
                       {:day :thu
                        :start (if active? 500 1500)
                        :end 2000
                        :frozen-from (if active? -28799500 -28798500)
                        :active? active?
                        :frozen? true
                        :today? true
                        :label "Thu 23:00–23:59"})
         status (merge {:ok true
                        :state state
                        :now 1000
                        :zone "UTC"
                        :zone-pending nil
                        :clock-suspect false
                        :safety-stopped false
                        :sleep-refused false
                        :unfrozen-at nil
                        :unfrozen-label (when locked? "Thu 23:59")
                        :schedule schedule
                        :occurrences (cond-> [] default-occ (conj default-occ))
                        :version "0.1.0"}
                       overrides)
         deadline (when locked?
                    (some->> (:occurrences status)
                             (filter :frozen?)
                             seq
                             (map :end)
                             (apply max)))]
     (if (and locked? (not (contains? overrides :unfrozen-at)))
       (assoc status :unfrozen-at deadline)
       status))))

(defn occ [day start end & {:keys [active? frozen? today?] :or {today? true}}]
  {:day day :start start :end end :frozen-from (- start (* 8 60 60 1000))
   :active? (boolean active?) :frozen? (boolean frozen?) :today? today?
   :label (str ({:mon "Mon" :tue "Tue" :wed "Wed"} day) " "
               (format "%02d:%02d" (mod (quot start 3600000) 24) (mod (quot start 60000) 60)) "–"
               (format "%02d:%02d" (mod (quot end 3600000) 24) (mod (quot end 60000) 60)))})

(defn state-for [status]
  (:state
   (ui/handle-event
    {:form nil
     :form-dirty? false
     :form-revision 0
     :schedule-generation 0
     :schedule-action-seq 0
     :uninstall-generation 0
     :uninstall-token-seq 0}
    [:status status])))

(defn growth-token [status baseline candidate & {:keys [activates-now confirm-until-at]
                                                  :or {activates-now false
                                                       confirm-until-at 3000}}]
  {:baseline baseline
   :candidate candidate
   :zone (:zone status)
   :authority-frozen (ui/canonical-frozen-signatures status)
   :authority-unfrozen-at (:unfrozen-at status)
   :confirm-until-at confirm-until-at
   :activates-now activates-now})

(deftest menu-title-precedence
  (is (= "☾ ⚠" (ui/menu-title {:status :daemon-down})))
  (is (= "☾ ⚠" (ui/menu-title {:status nil})))
  (is (= "☾ until 07:00"
         (ui/menu-title
          {:status (daemon-status
                    :active sched/empty-schedule
                    {:now (h 23 30)
                     :occurrences [(occ :mon (h 23 0) (h 31 0)
                                        :active? true :frozen? true)]})})))
  (testing "within 5 min shows a countdown even when frozen"
    (is (= "☾ in 4:12"
           (ui/menu-title
            {:status (daemon-status
                      :frozen sched/empty-schedule
                      {:now (h 22 0)
                       :occurrences
                       [(occ :mon (+ (h 22 0) (* 4 60000) 12000)
                             (h 31 0) :frozen? true)]})}))))
  (is (= "☾ 23:00 🔒"
         (ui/menu-title
          {:status (daemon-status
                    :frozen sched/empty-schedule
                    {:now (h 16 0)
                     :occurrences [(occ :mon (h 23 0) (h 31 0)
                                        :frozen? true)]})})))
  (is (= "☾ 23:00"
         (ui/menu-title
          {:status (daemon-status
                    :open sched/empty-schedule
                    {:now (h 12 0)
                     :occurrences [(occ :mon (h 23 0) (h 31 0)
                                        :today? true)]})})))
  (is (= "☾ Tue 23:00"
         (ui/menu-title
          {:status (daemon-status
                    :open sched/empty-schedule
                    {:now (h 12 0)
                     :occurrences [(occ :tue (h 47 0) (h 55 0)
                                        :today? false)]})})))
  (is (= "☾"
         (ui/menu-title
          {:status (daemon-status :open sched/empty-schedule
                                  {:now (h 12 0) :occurrences []})}))))

(deftest malformed-status-is-fail-closed-for-all-live-consumers
  (let [malformed (assoc (daemon-status :open)
                         :occurrences
                         [{:day :mon :start nil :end 2 :frozen-from 1
                           :frozen? false :active? false :today? true
                           :label "Mon 23:00–07:00"}])
        active-looking (assoc malformed :state :active
                              :safety-stopped false)]
    (is (not (ui/successful-status? malformed)))
    (is (= "☾ ⚠" (ui/menu-title {:status malformed})))
    (is (false? (:enabled?
                 (first (filter #(= "Uninstall…" (:label %))
                                (ui/menu-items {:status malformed}))))))
    (is (not (:post? (ui/notify-decision
                      {:status malformed :notified #{}}))))
    (is (not (ui/should-relock? {:status active-looking})))))

(deftest menu-items-lines
  (is (= ["Daemon not responding" "Settings…" "Uninstall…"]
         (map :label (ui/menu-items {:status :daemon-down}))))
  (let [labels (map :label
                    (ui/menu-items
                     {:status (daemon-status
                               :frozen
                               sched/empty-schedule
                               {:now (h 16 0)
                                :unfrozen-label "Tue 07:00"
                                :zone-pending "Europe/Paris"
                                :clock-suspect true
                                :sleep-refused true
                                :occurrences [(occ :mon (h 23 0) (h 31 0)
                                                   :frozen? true)]})}))]
    (is (= ["Mon 23:00–07:00" "Frozen until Tue 07:00" "Time zone change pending: Europe/Paris"
            "Clock unverified" "Couldn't put the Mac to sleep" "Settings…" "Uninstall…"]
           labels)))
  (let [items (ui/menu-items {:status (daemon-status :open)})]
    (is (= [:open-settings] (:action (first (filter #(= "Settings…" (:label %)) items)))))
    (is (= [:open-uninstall] (:action (first (filter #(= "Uninstall…" (:label %)) items)))))))

(deftest form-schedule-roundtrip
  (let [sch (assoc sched/empty-schedule :mon {:start "23:00" :end "07:00"} :wed {:start "22:30" :end "06:30"})]
    (is (= sch (ui/form->schedule (ui/schedule->form sch)))))
  (is (= {:enabled? false :start "23:00" :end "07:00"} (get (ui/schedule->form sched/empty-schedule) :tue))))

(deftest copy-monday-fills-all-rows
  (let [st {:status (daemon-status :open)
            :form (-> (ui/schedule->form sched/empty-schedule)
                      (assoc :mon {:enabled? true :start "22:00" :end "06:00"}))}
        {:keys [state]} (ui/handle-event st [:copy-monday])]
    (is (every? #(= {:enabled? true :start "22:00" :end "06:00"} (get (:form state) %)) sched/days))))

(deftest save-sends-dry-run
  (let [st (state-for (daemon-status :open))
        edited (:state (ui/handle-event st [:toggle :mon true]))
        {:keys [state request request-context]} (ui/handle-event edited [:save])]
    (is (= {:op :set-schedule
            :schedule (assoc sched/empty-schedule
                             :mon {:start "23:00" :end "07:00"})
            :edit-mode :open
            :dry-run true}
           request))
    (is (nil? (:error state)))
    (is (:form-dirty? state))
    (is (= (:form-revision edited) (:form-revision state)))
    (is (= {:generation (:schedule-generation state)
            :revision (:form-revision state)
            :action-token 1
            :edit-mode :open}
           request-context))))

(deftest confirm-required-opens-panel-then-resends
  (let [fz [{:day :mon :start 1 :end 2
             :frozen-from -28799999 :frozen? true :active? false
             :today? true :label "Mon 00:00–00:00" :ignored "display-only"}]
        sig [{:day :mon :start 1 :end 2}]
        st0 (state-for (daemon-status :open))
        st (:state (ui/handle-event st0 [:toggle :mon true]))
        saved (ui/handle-event st [:save])
        context (merge {:request (:request saved)} (:request-context saved))
        req (:request saved)
        r1 (ui/handle-event (:state saved) [:daemon-response
                                {:ok false :error {:code :confirm-required
                                                   :message "can't be undone until Tue 07:00"
                                                   :freezes-now fz}}
                                context])]
    (is (= :confirm-freeze (get-in r1 [:state :panel :kind])))
    (is (= "can't be undone until Tue 07:00" (get-in r1 [:state :panel :message])))
    (is (= sig (get-in r1 [:state :panel :freezes-now])))
    (let [r2 (ui/handle-event (:state r1) [:confirm-freeze])]
      (is (nil? (get-in r2 [:state :panel])))
      (is (= sig (get-in r2 [:request :confirm-freeze])))
      (is (= :open (get-in r2 [:request :edit-mode])))
      (is (= :set-schedule (get-in r2 [:request :op])))
      (is (= [:ok (:request r2)]
             (proto/read-request (.getBytes (pr-str (:request r2)) "UTF-8"))))
      (is (> (get-in r2 [:request-context :action-token])
             (get-in saved [:request-context :action-token]))))))

(deftest malformed-freeze-confirmation-data-fails-closed
  (let [base (state-for (daemon-status :open))
        st (:state (ui/handle-event base [:toggle :mon true]))
        saved (ui/handle-event st [:save])
        req (:request saved)
        context (merge {:request req} (:request-context saved))
        malformed [nil
                   {}
                   [{:day :mon :start 1}]
                   [{:day :nope :start 1 :end 2}]
                   [{:day :mon :start "1" :end 2}]]]
    (doseq [freezes-now malformed]
      (let [{:keys [state request]}
            (ui/handle-event
             (:state saved)
             [:daemon-response
              (cond-> {:ok true :applied false}
                (some? freezes-now) (assoc :freezes-now freezes-now))
              context])]
        (is (= :daemon-down (:status state)) (pr-str freezes-now))
        (is (= "Daemon not responding." (:error state)) (pr-str freezes-now))
        (is (nil? (:panel state)) (pr-str freezes-now))
        (is (nil? request) (pr-str freezes-now))))
    (let [{:keys [state request]}
          (ui/handle-event
           (:state saved)
           [:daemon-response
            {:ok false :error {:code :confirm-required
                               :message "confirm"
                               :freezes-now [{:day :mon :start 1}]}}
            context])]
      (is (= :daemon-down (:status state)))
      (is (= "Daemon not responding." (:error state)))
      (is (nil? (:panel state)))
      (is (nil? request)))))

(deftest malformed-synthetic-freeze-panel-cannot-submit
  (let [form (assoc (ui/schedule->form sched/empty-schedule)
                    :mon {:enabled? true :start "23:00" :end "07:00"})
        state {:status (daemon-status :open)
               :baseline-schedule sched/empty-schedule
               :form form
               :form-dirty? true
               :form-revision 3
               :schedule-generation 2
               :schedule-action-seq 4
               :panel {:kind :confirm-freeze
                       :message "confirm"
                       :schedule (ui/form->schedule form)
                       :freezes-now [{:day :mon :start 1}]
                       :schedule-generation 2
                       :form-revision 3
                       :edit-mode :open}}
        result (ui/handle-event state [:confirm-freeze])]
    (is (= state (:state result)))
    (is (nil? (:request result)))))

(deftest cancel-leaves-state
  (let [form (assoc (ui/schedule->form sched/empty-schedule)
                    :wed {:enabled? true :start "11:35" :end "11:50"})
        st {:status (daemon-status :open)
            :panel {:kind :confirm-freeze
                    :message "confirm"
                    :schedule (ui/form->schedule form)
                    :freezes-now [{:day :wed}]}
            :form form
            :form-dirty? true
            :form-revision 3
            :error "old error"}
        {:keys [state request]} (ui/handle-event st [:cancel-panel])]
    (is (nil? (:panel state)))
    (is (nil? request))
    (is (= form (:form state)))
    (is (:form-dirty? state))
    (is (= 3 (:form-revision state)))
    (is (nil? (:error state)))))

(deftest errors-shown-verbatim
  (let [st {:form (ui/schedule->form sched/empty-schedule)}
        {:keys [state]} (ui/handle-event st [:daemon-response {:ok false :error {:code :window :message "blocks need at least 9 h between them."}}])]
    (is (= "blocks need at least 9 h between them." (:error state)))
    (is (nil? (:panel state)))))

(defn labels-in [hiccup]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (and (map? x) (:label x)) (swap! acc conj (:label x))) x) hiccup)
    @acc))

(defn nodes-with-tag [hiccup tag]
  (let [acc (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (vector? x) (= tag (first x)) (map? (second x)))
         (swap! acc conj x))
       x)
     hiccup)
    @acc))

(defn button-props [hiccup label]
  (second (first (filter #(= label (:label (second %)))
                         (nodes-with-tag hiccup :button)))))

(defn menu-props [state label]
  (first (filter #(= label (:label %)) (ui/menu-items state))))

(deftest complete-status-validates-every-occurrence-field
  (let [complete (occ :mon 100000 200000)
        status (daemon-status :open sched/empty-schedule
                              {:occurrences [complete]})
        bad-values {:day :noday
                    :start "100000"
                    :end "200000"
                    :frozen-from "0"
                    :frozen? 1
                    :active? 0
                    :today? nil
                    :label :not-a-string}]
    (is (ui/successful-status? status))
    (is (ui/uninstall-available? {:status status}))
    (doseq [field [:day :start :end :frozen-from :frozen?
                   :active? :today? :label]]
      (testing (str "missing " field)
        (let [malformed (assoc status :occurrences [(dissoc complete field)])]
          (is (not (ui/successful-status? malformed)))
          (is (not (ui/uninstall-available? {:status malformed})))))
      (testing (str "wrong type/value " field)
        (let [malformed (assoc status :occurrences
                               [(assoc complete field (get bad-values field))])]
          (is (not (ui/successful-status? malformed)))
          (is (not (ui/uninstall-available? {:status malformed}))))))
    (is (not (ui/successful-status?
              (assoc status :occurrences [complete "not-an-occurrence"]))))))

(deftest uninstall-availability-and-menu-data-fail-closed
  (let [open (daemon-status :open)
        malformed-occurrence
        (assoc open :occurrences [(dissoc (occ :mon 100000 200000)
                                          :frozen-from)])]
    (testing "only a complete successful open status offers an action"
      (let [state {:status open}
            uninstall (menu-props state "Uninstall…")
            settings (menu-props state "Settings…")]
        (is (ui/uninstall-available? state))
        (is (true? (:enabled? uninstall)))
        (is (= [:open-uninstall] (:action uninstall)))
        (is (true? (:enabled? settings)))
        (is (= [:open-settings] (:action settings)))))
    (doseq [[label status]
            [[:startup nil]
             [:absent :daemon-down]
             [:not-ok (assoc open :ok false)]
             [:malformed-status (dissoc open :version)]
             [:malformed-occurrence malformed-occurrence]
             [:frozen (daemon-status :frozen)]
             [:active (daemon-status :active)]
             [:safety-stopped-active
              (daemon-status :active sched/empty-schedule
                             {:safety-stopped true})]]]
      (testing (name label)
        (let [state {:status status}
              uninstall (menu-props state "Uninstall…")
              settings (menu-props state "Settings…")]
          (is (not (ui/uninstall-available? state)))
          (is (false? (:enabled? uninstall)))
          (is (not (contains? uninstall :action)))
          (is (true? (:enabled? settings)))
          (is (= [:open-settings] (:action settings)))
          (when-not (ui/successful-status? status)
            (is (= "Daemon not responding"
                   (:label (first (ui/menu-items state))))
                "invalid or unavailable status is not interpreted as authority")))))))

(deftest uninstall-panel-view-is-natively-fail-closed
  (let [open (daemon-status :open)
        current {:status open
                 :form (ui/schedule->form sched/empty-schedule)
                 :uninstall-generation 4
                 :panel {:kind :uninstall
                         :uninstall-generation 4
                         :uninstall-token 9}}
        current-button (button-props (ui/view current) "Uninstall")]
    (is (true? (:sensitive current-button)))
    (is (= {:click [[:confirm-uninstall]]} (:on current-button)))
    (doseq [[label state]
            [[:stale-generation
              (assoc-in current [:panel :uninstall-generation] 3)]
             [:missing-token
              (update current :panel dissoc :uninstall-token)]
             [:daemon-down
              (assoc current :status :daemon-down)]
             [:frozen
              (assoc current :status (daemon-status :frozen))]
             [:active
              (assoc current :status (daemon-status :active))]]]
      (testing (name label)
        (let [button (button-props (ui/view state) "Uninstall")]
          (is (false? (:sensitive button)))
          (is (not (contains? button :on))))))))

(deftest uninstall-open-confirm-cancel-and-duplicate-events
  (let [base {:status (daemon-status :open)
              :form (ui/schedule->form sched/empty-schedule)
              :uninstall-generation 4
              :uninstall-token-seq 8}
        opened (ui/handle-event base [:open-uninstall])
        open-state (:state opened)
        panel (:panel open-state)]
    (is (nil? (:request opened)))
    (is (= {:kind :uninstall
            :uninstall-generation 4
            :uninstall-token 9}
           panel))
    (is (= 9 (:uninstall-token-seq open-state)))
    (let [confirmed (ui/handle-event open-state [:confirm-uninstall])
          confirmed-state (:state confirmed)
          context (:request-context confirmed)]
      (is (= {:op :uninstall} (:request confirmed)))
      (is (= {:uninstall-generation 4 :uninstall-token 9} context))
      (is (= context (:uninstall-request confirmed-state)))
      (is (nil? (:panel confirmed-state)))
      (is (ui/uninstall-request-current? confirmed-state context))
      (testing "the panel token can emit only once"
        (let [duplicate (ui/handle-event confirmed-state
                                         [:confirm-uninstall])]
          (is (= confirmed-state (:state duplicate)))
          (is (nil? (:request duplicate)))
          (is (nil? (:request-context duplicate))))))
    (testing "Cancel consumes an open panel without I/O"
      (let [cancelled (ui/handle-event open-state [:cancel-panel])
            cancelled-state (:state cancelled)]
        (is (nil? (:panel cancelled-state)))
        (is (nil? (:request cancelled)))
        (is (nil? (:uninstall-request cancelled-state)))
        (is (= 9 (:uninstall-token-seq cancelled-state)))
        (is (= cancelled-state
               (:state (ui/handle-event cancelled-state
                                       [:cancel-panel]))))))))

(deftest uninstall-reducers-are-exact-no-ops-without-authority
  (let [open (daemon-status :open)
        unavailable [nil
                     :daemon-down
                     (dissoc open :version)
                     (assoc open :occurrences
                            [(dissoc (occ :mon 100000 200000)
                                     :frozen-from)])
                     (daemon-status :frozen)
                     (daemon-status :active)
                     (daemon-status :active sched/empty-schedule
                                    {:safety-stopped true})]]
    (doseq [status unavailable]
      (let [base {:status status
                  :uninstall-generation 3
                  :uninstall-token-seq 7
                  :panel {:kind :uninstall
                          :uninstall-generation 3
                          :uninstall-token 7}
                  :error "keep"}]
        (doseq [event [[:open-uninstall] [:confirm-uninstall]]]
          (let [result (ui/handle-event base event)]
            (is (= base (:state result)) (pr-str [status event]))
            (is (nil? (:request result)) (pr-str [status event]))
            (is (nil? (:request-context result))
                (pr-str [status event]))))))
    (testing "confirm without the matching current panel is ignored"
      (doseq [panel [nil
                     {:kind :confirm-freeze}
                     {:kind :uninstall
                      :uninstall-generation 2
                      :uninstall-token 7}
                     {:kind :uninstall
                      :uninstall-generation 3}]]
        (let [state {:status open
                     :uninstall-generation 3
                     :uninstall-token-seq 7
                     :panel panel}
              result (ui/handle-event state [:confirm-uninstall])]
          (is (= state (:state result)) (pr-str panel))
          (is (nil? (:request result)) (pr-str panel)))))))

(deftest status-transitions-replace-and-dismiss-uninstall-panels
  (let [open (daemon-status :open)
        open-panel {:status open
                    :form (ui/schedule->form sched/empty-schedule)
                    :uninstall-generation 3
                    :uninstall-token-seq 8
                    :panel {:kind :uninstall
                            :uninstall-generation 3
                            :uninstall-token 8}}
        cases [[:frozen (daemon-status :frozen)
                "Go To Sleep cannot be uninstalled until Thu 23:59."]
               [:active (daemon-status :active)
                "Go To Sleep cannot be uninstalled until Thu 23:59."]
               [:safety-stopped-active
                (daemon-status :active sched/empty-schedule
                               {:safety-stopped true})
                "Go To Sleep cannot be uninstalled until Thu 23:59."]
               [:daemon-down :daemon-down "Daemon not responding."]
               [:malformed (dissoc open :version) "Daemon not responding."]]]
    (doseq [[label status message] cases]
      (testing (name label)
        (let [unavailable (:state
                           (ui/handle-event open-panel [:status status]))
              refusal (:panel unavailable)
              reopened (:state
                        (ui/handle-event unavailable [:status open]))]
          (is (= 4 (:uninstall-generation unavailable)))
          (is (= :uninstall-refused (:kind refusal)))
          (is (= message (:message refusal)))
          (is (true? (:status-derived? refusal)))
          (is (= 4 (:uninstall-generation refusal)))
          (is (nil? (:uninstall-request unavailable)))
          (is (= 5 (:uninstall-generation reopened)))
          (is (nil? (:panel reopened)))
          (is (ui/uninstall-available? reopened)))))
    (testing "an existing status refusal follows unavailable-state changes"
      (let [down (:state
                  (ui/handle-event open-panel [:status :daemon-down]))
            frozen (:state
                    (ui/handle-event down
                                     [:status (daemon-status :frozen)]))
            active (:state
                    (ui/handle-event frozen
                                     [:status (daemon-status :active)]))]
        (is (= "Daemon not responding." (get-in down [:panel :message])))
        (is (= "Go To Sleep cannot be uninstalled until Thu 23:59."
               (get-in frozen [:panel :message])))
        (is (= "Go To Sleep cannot be uninstalled until Thu 23:59."
               (get-in active [:panel :message])))
        (is (= 4 (:uninstall-generation active))
            "unavailable-to-unavailable changes stay in one generation")))
    (testing "healthy open polls do not advance an existing open generation"
      (let [polled (:state
                    (ui/handle-event open-panel [:status open]))]
        (is (= 3 (:uninstall-generation polled)))
        (is (= (:panel open-panel) (:panel polled)))))))

(deftest open-non-open-open-invalidates-old-uninstall-intent
  (let [base {:status (daemon-status :open)
              :form (ui/schedule->form sched/empty-schedule)
              :uninstall-generation 2
              :uninstall-token-seq 4}
        opened (:state (ui/handle-event base [:open-uninstall]))
        confirmed (ui/handle-event opened [:confirm-uninstall])
        context (:request-context confirmed)
        queued (:state confirmed)
        frozen (:state
                (ui/handle-event queued
                                 [:status (daemon-status :frozen)]))
        reopened (:state
                  (ui/handle-event frozen
                                   [:status (daemon-status :open)]))]
    (is (ui/uninstall-request-current? queued context))
    (is (= 3 (:uninstall-generation frozen)))
    (is (nil? (:uninstall-request frozen)))
    (is (= 4 (:uninstall-generation reopened)))
    (is (not (ui/uninstall-request-current? reopened context)))
    (is (nil? (:panel reopened)))
    (is (nil? (:request
               (ui/handle-event reopened
                                [:confirm-uninstall]))))))

(deftest stale-uninstall-responses-cannot-mutate-newer-state
  (let [current-context {:uninstall-generation 4 :uninstall-token 8}
        current {:status (daemon-status :open)
                 :form (ui/schedule->form sched/empty-schedule)
                 :uninstall-generation 4
                 :uninstall-token-seq 9
                 :uninstall-request current-context}
        request-context (assoc current-context :request {:op :uninstall})
        newer (assoc current
                     :uninstall-generation 5
                     :uninstall-request nil
                     :panel {:kind :uninstall
                             :uninstall-generation 5
                             :uninstall-token 9})
        responses [nil
                   {:ok true}
                   {:ok false
                    :error {:code :frozen
                            :message "Go To Sleep cannot be uninstalled until Fri 07:00."}}]]
    (doseq [response responses]
      (testing (str "stale response " (pr-str response))
        (let [result (ui/handle-event
                      newer
                      [:daemon-response response request-context])]
          (is (= newer (:state result)))
          (is (nil? (:request result))))))
    (testing "a matching refusal consumes the token and is shown once"
      (let [response {:ok false
                      :error {:code :frozen
                              :message "Go To Sleep cannot be uninstalled until Fri 07:00."}}
            first-result (ui/handle-event
                          current
                          [:daemon-response response request-context])
            first-state (:state first-result)
            duplicate (ui/handle-event
                       first-state
                       [:daemon-response response request-context])]
        (is (nil? (:uninstall-request first-state)))
        (is (= :uninstall-refused (get-in first-state [:panel :kind])))
        (is (= "Go To Sleep cannot be uninstalled until Fri 07:00."
               (get-in first-state [:panel :message])))
        (is (= first-state (:state duplicate)))))
    (testing "a matching connectivity result is cleared by later healthy status"
      (let [failed (:state
                    (ui/handle-event
                     current
                     [:daemon-response nil request-context]))
            recovered (:state
                       (ui/handle-event failed
                                        [:status (daemon-status :open)]))]
        (is (= "Daemon not responding." (get-in failed [:panel :message])))
        (is (nil? (:panel recovered)))
        (let [locked (:state
                      (ui/handle-event
                       failed
                       [:status (daemon-status :frozen)]))]
          (is (= "Go To Sleep cannot be uninstalled until Thu 23:59."
                 (get-in locked [:panel :message])))
          (is (true? (get-in locked [:panel :status-derived?]))))))
    (testing "a matching success consumes the request without inventing UI"
      (let [succeeded (:state
                       (ui/handle-event
                        current
                        [:daemon-response {:ok true} request-context]))]
        (is (nil? (:uninstall-request succeeded)))
        (is (nil? (:panel succeeded)))
        (is (nil? (:error succeeded)))))))

(deftest status-consistency-selects-three-edit-modes
  (let [open (daemon-status :open)
        frozen (daemon-status :frozen)
        active (daemon-status :active)]
    (is (= :open (ui/edit-mode {:status open})))
    (is (= :growth-only (ui/edit-mode {:status frozen})))
    (is (= :growth-only (ui/edit-mode {:status active})))
    (doseq [[label status]
            [[:startup nil]
             [:down :daemon-down]
             [:missing-version (dissoc open :version)]
             [:open-with-frozen (assoc open :occurrences
                                       [(occ :mon 1500 2000 :frozen? true)])]
             [:frozen-without-frozen
              (assoc frozen :occurrences [] :unfrozen-at nil)]
             [:frozen-with-active
              (assoc frozen :state :frozen
                     :occurrences [(occ :mon 500 2000
                                        :frozen? true :active? true)])]
             [:active-without-active
              (assoc active :state :active
                     :occurrences [(occ :mon 1500 2000 :frozen? true)])]
             [:wrong-deadline (update frozen :unfrozen-at inc)]]]
      (testing (name label)
        (is (not (ui/successful-status? status)))
        (is (= :read-only (ui/edit-mode {:status status})))))))

(deftest growth-only-controls-track-semantic-dirty-state
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"}
                        :wed {:start "22:00" :end "06:00"})
        state (state-for (daemon-status :frozen baseline))
        pristine (ui/view state)
        switches (nodes-with-tag pristine :switch)
        entries (nodes-with-tag pristine :entry)]
    (is (= 7 (count switches)))
    (is (false? (:sensitive (second (nth switches 0)))))
    (is (true? (:sensitive (second (nth switches 1)))))
    (is (false? (:sensitive (button-props pristine "Copy Monday to all days"))))
    (is (false? (:sensitive (button-props pristine "Revert"))))
    (is (false? (:sensitive (button-props pristine "Save extensions"))))
    (is (= 4 (count (filter #(true? (:sensitive (second %))) entries))))
    (is (some #{"Schedule is frozen until Thu 23:59. Blocks may be added or made longer; shrinking or moving waits until afterward."}
              (labels-in pristine)))
    (is (some #{"Add a block or make an existing block longer to save."}
              (labels-in pristine)))
    (let [added (:state (ui/handle-event state [:toggle :tue true]))
          added-view (ui/view added)
          undone (:state (ui/handle-event added [:toggle :tue false]))]
      (is (:form-dirty? added))
      (is (true? (:sensitive (button-props added-view "Revert"))))
      (is (true? (:sensitive (button-props added-view "Save extensions"))))
      (is (false? (:form-dirty? undone)))
      (is (false? (:sensitive (button-props (ui/view undone)
                                            "Save extensions")))))))

(deftest local-growth-matrix-and-revert
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"}
                        :wed {:start "22:00" :end "06:00"})
        base (state-for (daemon-status :active baseline))
        candidate (fn [events]
                    (reduce (fn [s event]
                              (:state (ui/handle-event s event)))
                            base events))
        accepted [[[:edit-start :mon "22:30"]]
                  [[:edit-end :mon "08:00"]]
                  [[:edit-start :mon "22:30"] [:edit-end :mon "08:00"]]
                  [[:toggle :tue true]]
                  [[:edit-start :mon "22:30"] [:edit-end :wed "07:00"]]]
        rejected [[[:edit-start :mon "23:30"]]
                  [[:edit-end :mon "06:30"]]
                  [[:edit-start :mon "22:30"] [:edit-end :mon "06:30"]]
                  [[:edit-start :mon "23:30"] [:edit-end :mon "08:00"]]
                  [[:edit-start :mon "22:30"] [:edit-end :wed "05:30"]]]]
    (doseq [events accepted]
      (let [state (candidate events)
            result (ui/handle-event state [:save])]
        (is (:form-dirty? state) (pr-str events))
        (is (= :set-schedule (get-in result [:request :op])) (pr-str events))
        (is (= :growth-only (get-in result [:request :edit-mode])))))
    (doseq [events rejected]
      (let [state (candidate events)
            view (ui/view state)
            result (ui/handle-event state [:save])]
        (is (:form-dirty? state) (pr-str events))
        (is (false? (:sensitive
                     (button-props view "Save extensions"))) (pr-str events))
        (is (not (contains? (button-props view "Save extensions") :on)))
        (is (nil? (:request result)) (pr-str events))
        (is (some #{ "While frozen, existing blocks must keep the same or an earlier start and the same or a later end."}
                  (labels-in view)))))
    (testing "baseline switches cannot be disabled, and Revert restores authority"
      (is (= base (:state (ui/handle-event base [:toggle :mon false]))))
      (let [grown (candidate [[:edit-start :mon "22:30"]])
            reverted (:state (ui/handle-event grown [:revert]))]
        (is (= baseline (ui/form->schedule (:form reverted))))
        (is (false? (:form-dirty? reverted)))))))

(deftest local-growth-classifier-is-all-or-nothing
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"}
                        :wed {:start "22:00" :end "06:00"})]
    (is (not (ui/growth-candidate? baseline baseline)))
    (is (not (ui/growth-candidate? baseline
                                   (assoc baseline :mon nil))))
    (is (not (ui/growth-candidate?
              baseline
              (assoc baseline
                     :mon {:start "22:00" :end "08:00"}
                     :wed {:start "22:30" :end "06:00"}))))
    (is (ui/growth-candidate?
         baseline
         (assoc baseline
                :mon {:start "22:00" :end "08:00"}
                :tue {:start "12:00" :end "13:00"})))))

(deftest growth-confirmation-cancel-and-immediate-lock-text
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :frozen baseline)
        state (state-for status)
        grown (:state (ui/handle-event state [:edit-start :mon "22:00"]))
        saved (ui/handle-event grown [:save])
        candidate (get-in saved [:request :schedule])
        token (growth-token status baseline candidate
                            :activates-now true
                            :confirm-until-at 4000)
        context (merge {:request (:request saved)}
                       (:request-context saved))
        response {:ok true :applied false :edit-mode :growth-only
                  :confirm-growth token
                  :confirm-until-label "Mon 08:00"
                  :activates-now true}
        panel-state (:state
                     (ui/handle-event (:state saved)
                                      [:daemon-response response context]))
        labels (labels-in (ui/view panel-state))]
    (is (= :confirm-growth (get-in panel-state [:panel :kind])))
    (is (some #{"Monday: 23:00–07:00 → 22:00–07:00"} labels))
    (is (some #{"Confirming will lock the screen immediately and keep it locked until Mon 08:00."}
              labels))
    (let [cancelled (ui/handle-event panel-state [:cancel-panel])]
      (is (nil? (:request cancelled)))
      (is (nil? (get-in cancelled [:state :panel])))
      (is (= candidate
             (ui/form->schedule (get-in cancelled [:state :form]))))
      (is (get-in cancelled [:state :form-dirty?])))
    (let [confirmed (ui/handle-event panel-state [:confirm-growth])]
      (is (= token (get-in confirmed [:request :confirm-growth])))
      (is (= :growth-only (get-in confirmed [:request :edit-mode])))
      (is (integer? (get-in confirmed [:request-context :action-token])))
      (is (nil? (:request
                 (ui/handle-event (:state confirmed) [:confirm-growth])))))))

(deftest growth-confirmation-uses-candidate-deadline
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)
        state (state-for status)
        grown (:state (ui/handle-event state [:edit-end :mon "09:00"]))
        saved (ui/handle-event grown [:save])
        candidate (get-in saved [:request :schedule])
        token (growth-token status baseline candidate
                            :confirm-until-at 5000)
        panel-state
        (:state
         (ui/handle-event
          (:state saved)
          [:daemon-response
           {:ok true :applied false :edit-mode :growth-only
            :confirm-growth token
            :confirm-until-label "Mon 09:00"
            :activates-now false}
           (merge {:request (:request saved)}
                  (:request-context saved))]))]
    (is (= "These changes cannot be undone until Mon 09:00."
           (get-in panel-state [:panel :message])))
    (is (= [{:day :mon
             :before "23:00–07:00"
             :after "23:00–09:00"}]
           (get-in panel-state [:panel :changes])))))

(deftest authority-generations-preserve-only-the-same-growth-authority
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        frozen-status (daemon-status
                       :frozen baseline
                       {:occurrences
                        [(occ :mon 1500 2000 :frozen? true)
                         (occ :wed 1600 2500 :frozen? true)]})
        frozen (state-for frozen-status)
        edited (:state (ui/handle-event frozen [:edit-start :mon "22:30"]))
        reversed-status (update frozen-status :occurrences
                                #(vec (reverse %)))
        reordered (:state (ui/handle-event edited [:status reversed-status]))
        active-status (assoc frozen-status
                             :state :active
                             :occurrences
                             (assoc-in (:occurrences frozen-status)
                                       [0 :active?] true))
        active (:state (ui/handle-event reordered [:status active-status]))
        changed-zone (:state
                      (ui/handle-event active
                                       [:status (assoc active-status
                                                      :zone "Europe/Paris")]))
        changed-frozen (:state
                        (ui/handle-event
                         active
                         [:status
                          (assoc active-status
                                 :occurrences
                                 [(assoc (first (:occurrences active-status))
                                         :end 2100)]
                                 :unfrozen-at 2100)]))
        down (:state (ui/handle-event active [:status :daemon-down]))
        restored (:state (ui/handle-event down [:status active-status]))]
    (is (= (:schedule-generation edited)
           (:schedule-generation reordered)
           (:schedule-generation active)))
    (is (= "22:30" (get-in active [:form :mon :start])))
    (is (:form-dirty? active))
    (is (> (:schedule-generation changed-zone)
           (:schedule-generation active)))
    (is (= baseline (ui/form->schedule (:form changed-zone))))
    (is (> (:schedule-generation changed-frozen)
           (:schedule-generation active)))
    (is (= baseline (ui/form->schedule (:form changed-frozen))))
    (is (> (:schedule-generation restored)
           (:schedule-generation down)
           (:schedule-generation active)))
    (is (false? (:form-dirty? restored)))))

(deftest other-client-confirmation-and-disconnect-reconcile-authority
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)
        state (state-for status)
        local (:state (ui/handle-event state [:edit-end :mon "09:00"]))
        saved (ui/handle-event local [:save])
        other-baseline (assoc baseline
                              :mon {:start "22:30" :end "08:00"})
        candidate (assoc other-baseline
                         :mon {:start "22:00" :end "09:00"})
        token (growth-token status other-baseline candidate
                            :confirm-until-at 5000)
        refreshed
        (:state
         (ui/handle-event
          (assoc (:state saved)
                 :form (ui/schedule->form candidate))
          [:daemon-response
           {:ok false
            :error {:code :confirm-required
                    :message "These changes cannot be undone until Mon 09:00."
                    :confirm-growth token
                    :confirm-until-label "Mon 09:00"
                    :activates-now false}}
           (merge {:request (assoc (:request saved) :schedule candidate)}
                  (:request-context saved))]))
        disconnected
        (:state
         (ui/handle-event
          (:state saved)
          [:daemon-response nil
           (merge {:request (:request saved)}
                  (:request-context saved))]))
        restored (:state
                  (ui/handle-event disconnected [:status status]))]
    (is (= other-baseline (:baseline-schedule refreshed)))
    (is (= :confirm-growth (get-in refreshed [:panel :kind])))
    (is (= candidate (get-in refreshed [:panel :schedule])))
    (is (= :read-only (ui/edit-mode disconnected)))
    (is (> (:schedule-generation disconnected)
           (:schedule-generation (:state saved))))
    (is (= baseline
           (ui/form->schedule (:form disconnected))))
    (is (false? (:form-dirty? disconnected)))
    (is (= "Daemon not responding." (:error disconnected)))
    (is (= :growth-only (ui/edit-mode restored)))
    (is (> (:schedule-generation restored)
           (:schedule-generation disconnected)))))

(deftest other-client-non-containing-change-stays-dirty-with-growth-error
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)
        state (state-for status)
        grown (:state (ui/handle-event state [:edit-end :mon "08:00"]))
        saved (ui/handle-event grown [:save])
        message (str "Schedule is frozen until Mon 07:00. "
                     "Until then, blocks may only be added or made longer.")
        result (ui/handle-event
                (:state saved)
                [:daemon-response
                 {:ok false
                  :error {:code :growth-only :message message}}
                 (merge {:request (:request saved)}
                        (:request-context saved))])]
    (is (= message (get-in result [:state :error])))
    (is (get-in result [:state :form-dirty?]))
    (is (nil? (get-in result [:state :panel])))))

(deftest growth-and-window-errors-remain-inline-and-dirty
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)]
    (doseq [[code message]
            [[:growth-only
              "Schedule is frozen until Mon 07:00. Until then, blocks may only be added or made longer."]
             [:window
              "Monday's block would leave less than nine hours before Tuesday."]]]
      (let [state (state-for status)
            grown (:state (ui/handle-event state [:edit-end :mon "08:00"]))
            saved (ui/handle-event grown [:save])
            result
            (ui/handle-event
             (:state saved)
             [:daemon-response
              {:ok false :error {:code code :message message}}
              (merge {:request (:request saved)}
                     (:request-context saved))])]
        (is (= message (get-in result [:state :error])) (name code))
        (is (get-in result [:state :form-dirty?]) (name code))
        (is (nil? (get-in result [:state :panel])) (name code))))))

(deftest view-renders-rows-and-error
  (let [st {:status (daemon-status :open)
            :form (ui/schedule->form sched/empty-schedule) :error "bad times"}
        ls (labels-in (ui/view st))]
    (is (every? (set ls) (vals sched/day-names)))
    (is (some #{"Save"} ls))
    (is (some #{"Copy Monday to all days"} ls))
    (is (some #{"bad times"} ls))))

(deftest view-renders-panels
  (let [base {:status (daemon-status :open)
              :form (ui/schedule->form sched/empty-schedule)}
        settings (ui/view base)
        confirmation (ui/view
                      (assoc base :panel {:kind :confirm-freeze :message "m"}))
        [_ _ settings-form settings-panel] settings
        [_ _ confirmation-form confirmation-panel] confirmation
        confirmation-labels (labels-in confirmation)]
    (testing "the form and panel containers keep stable positions"
      (is (true? (:visible (second settings-form))))
      (is (false? (:visible (second settings-panel))))
      (is (false? (:visible (second confirmation-form))))
      (is (true? (:visible (second confirmation-panel)))))
    (testing "the hidden form remains mounted behind the confirmation panel"
      (is (some #{"Save"} confirmation-labels))
      (is (every? (set confirmation-labels) (vals sched/day-names))))
    (is (some #{"Confirm"} confirmation-labels))
    (is (some #{"m"} confirmation-labels))
    (is (some #{"This removes Go To Sleep and your schedule."}
              (labels-in (ui/view (assoc base :panel {:kind :uninstall})))))))

(deftest notify-decision-cases
  (let [status (fn [state now occs]
                 (let [occs (mapv
                             (fn [o]
                               (case state
                                 :active (assoc o :frozen? true :active? true)
                                 :frozen (assoc o :frozen? true :active? false)
                                 :open (assoc o :frozen? false :active? false)))
                             occs)]
                   {:status (daemon-status state sched/empty-schedule
                                          {:now now :occurrences occs})}))
        soon (occ :mon (h 23 0) (h 31 0))]
    (let [d (ui/notify-decision (assoc (status :frozen (- (h 23 0) 240000) [soon]) :notified #{}))]
      (is (:post? d))
      (is (= "Go To Sleep" (:title d)))
      (is (= (h 23 0) (:start d)))
      (is (= "Bedtime at 23:00 — the screen locks in 4 minutes." (:body d))))
    (is (= "Bedtime at 23:00 — the screen locks in 1 minute."
           (:body (ui/notify-decision (assoc (status :frozen (- (h 23 0) 30000) [soon]) :notified #{})))))
    (is (not (:post? (ui/notify-decision (assoc (status :frozen (- (h 23 0) 240000) [soon]) :notified #{(h 23 0)})))))
    (is (not (:post? (ui/notify-decision (assoc (status :active (h 23 30)
                                                        [(occ :mon (h 23 0) (h 31 0) :active? true)]) :notified #{})))))
    (is (not (:post? (ui/notify-decision (assoc (status :open (- (h 23 0) 400000) [soon]) :notified #{})))))
    (is (not (:post? (ui/notify-decision (assoc (status :open (+ (h 23 0) 1000) [soon]) :notified #{})))))
    (is (not (:post? (ui/notify-decision {:status :daemon-down :notified #{}}))))))

(deftest relock-only-when-active
  (is (ui/should-relock? {:status (daemon-status :active)}))
  (is (not (ui/should-relock?
            {:status (daemon-status :active sched/empty-schedule
                                    {:safety-stopped true})})))
  (is (not (ui/should-relock? {:status (daemon-status :frozen)})))
  (is (not (ui/should-relock? {:status :daemon-down})))
  (let [unlocked {"kCGSSessionOnConsoleKey" true
                  "CGSSessionScreenIsLocked" false}]
    (is (ui/should-relock? {:status (daemon-status :active)} unlocked 1000))
    (is (ui/should-relock? {:status (daemon-status :active)}
                           (dissoc unlocked "CGSSessionScreenIsLocked") 1000))
    (is (not (ui/should-relock?
              {:status (daemon-status :active sched/empty-schedule
                                      {:safety-stopped true})}
              unlocked 1000)))
    (is (not (ui/should-relock? {:status (daemon-status :active)
                                 :last-relock-mono-ms 500} unlocked 1499)))
    (is (ui/should-relock? {:status (daemon-status :active)
                            :last-relock-mono-ms 500} unlocked 1500))
    (is (not (ui/should-relock? {:status (daemon-status :active)}
                                (assoc unlocked "CGSSessionScreenIsLocked" true) 1000)))
    (is (not (ui/should-relock? {:status (daemon-status :active)}
                                (assoc unlocked "CGSSessionScreenIsLocked" 0) 1000)))
    (is (not (ui/should-relock? {:status (daemon-status :active)}
                                (assoc unlocked "kCGSSessionOnConsoleKey" 1) 1000)))))

(deftest event-actions-appends-values
  (testing "a click carries no value: the action passes through"
    (is (= [[:save]] (ui/event-actions {:glitter/dom-event {}} [[:save]]))))
  (testing "a switch appends its boolean, an entry its text"
    (is (= [[:toggle :mon true]]
           (ui/event-actions {:glitter/dom-event {:glitter/value true}} [[:toggle :mon]])))
    (is (= [[:edit-start :tue "22:30"]]
           (ui/event-actions {:glitter/dom-event {:glitter/value "22:30"}} [[:edit-start :tue]]))))
  (testing "false is a real value, not absence"
    (is (= [[:toggle :mon false]]
           (ui/event-actions {:glitter/dom-event {:glitter/value false}} [[:toggle :mon]]))))
  (testing "junk handler data yields no actions"
    (is (= [] (ui/event-actions {} nil)))
    (is (= [] (ui/event-actions {} :save)))))

(deftest event-actions-feed-handle-event
  (testing "the reshaped actions are what handle-event expects"
    (let [st {:status (daemon-status :open)
              :form (ui/schedule->form sched/empty-schedule)}
          [a] (ui/event-actions {:glitter/dom-event {:glitter/value true}} [[:toggle :wed]])
          {:keys [state]} (ui/handle-event st a)]
      (is (true? (get-in state [:form :wed :enabled?]))))
    (let [st {:status (daemon-status :open)
              :baseline-schedule sched/empty-schedule
              :form (assoc (ui/schedule->form sched/empty-schedule)
                           :fri {:enabled? true :start "23:00" :end "07:00"})}
          [a] (ui/event-actions {:glitter/dom-event {:glitter/value "21:45"}} [[:edit-end :fri]])
          {:keys [state]} (ui/handle-event st a)]
      (is (= "21:45" (get-in state [:form :fri :end]))))))

(deftest window-actions-set
  (is (contains? ui/window-actions :open-settings))
  (is (contains? ui/window-actions :open-uninstall))
  (is (not (contains? ui/window-actions :save))))

(deftest dry-run-response-opens-panel-or-applies
  (testing "a dry-run result that freezes a block now opens the confirm panel"
    (let [fz [{:day :mon :start 1 :end 2
               :frozen-from -28799999 :frozen? true :active? false
               :today? true :label "Mon 00:00–00:00"}]
          sig [{:day :mon :start 1 :end 2}]
          base (state-for (daemon-status :open))
          st (:state (ui/handle-event base [:toggle :mon true]))
          saved (ui/handle-event st [:save])
          req (:request saved)
          context (merge {:request req} (:request-context saved))
          {:keys [state request]} (ui/handle-event (:state saved) [:daemon-response
                                                       {:ok true :applied false :freezes-now fz
                                                        :confirm-until-label "Tue 07:00"}
                                                       context])]
      (is (= :confirm-freeze (get-in state [:panel :kind])))
      (is (= sig (get-in state [:panel :freezes-now])))
      (is (= (:schedule req) (get-in state [:panel :schedule])))
      (is (re-find #"Tue 07:00" (get-in state [:panel :message])))
      (is (nil? request))))
  (testing "a dry-run result that freezes nothing sends the real (non-dry) apply"
    (let [base (state-for (daemon-status :open))
          st (:state (ui/handle-event base [:toggle :mon true]))
          saved (ui/handle-event st [:save])
          req (:request saved)
          context (merge {:request req} (:request-context saved))
          {:keys [state request]} (ui/handle-event (:state saved) [:daemon-response
                                                       {:ok true :applied false :freezes-now []}
                                                       context])]
      (is (nil? (:panel state)))
      (is (= :set-schedule (:op request)))
      (is (nil? (:dry-run request)))
      (is (= :open (:edit-mode request)))
      (is (= {:start "23:00" :end "07:00"} (get-in request [:schedule :mon])))))
  (testing "an applied result installs its complete status before another action"
    (let [base (state-for (daemon-status :open))
          st (:state (ui/handle-event base [:toggle :wed true]))
          saved (ui/handle-event st [:save])
          context (merge {:request (:request saved)}
                         (:request-context saved))
          remote (assoc sched/empty-schedule
                        :wed {:start "23:00" :end "07:00"})
          response-status (daemon-status :open remote)
          {:keys [state]} (ui/handle-event
                           (:state saved)
                           [:daemon-response
                            {:ok true :applied true :edit-mode :open
                             :status response-status}
                            context])]
      (is (nil? (:panel state)))
      (is (nil? (:error state)))
      (is (false? (:form-dirty? state)))
      (is (= remote (:baseline-schedule state)))
      (is (= remote (ui/form->schedule (:form state))))
      (let [edited (:state (ui/handle-event state [:edit-start :wed "22:30"]))
            second-save (ui/handle-event edited [:save])]
        (is (= remote (:baseline-schedule edited)))
        (is (= "22:30" (get-in second-save [:request :schedule :wed :start])))
        (is (= (:schedule-generation edited)
               (get-in second-save [:request-context :generation])))))))

(deftest status-sync-respects-dirty-form
  (let [remote (assoc sched/empty-schedule :tue {:start "22:00" :end "06:00"})
        status (daemon-status :open remote)
        pristine (:state (ui/handle-event {:form nil :form-dirty? false} [:status status]))]
    (is (= remote (ui/form->schedule (:form pristine))))
    (testing "opening before the first status does not pin an empty form"
      (let [opened (:state (ui/handle-event {} [:open-settings]))
            synced (:state (ui/handle-event opened [:status status]))]
        (is (= remote (ui/form->schedule (:form synced))))))
    (testing "a user edit survives subsequent status polls"
      (let [edited (:state (ui/handle-event pristine [:edit-start :tue "21:30"]))
            polled (:state (ui/handle-event edited [:status status]))]
        (is (:form-dirty? polled))
        (is (= "21:30" (get-in polled [:form :tue :start])))))))

(deftest successful-status-clears-only-stale-connectivity-errors
  (let [status (daemon-status :open)
        initial (state-for status)
        edited (:state (ui/handle-event initial [:toggle :mon true]))
        saved (ui/handle-event edited [:save])
        failed-request
        (:state (ui/handle-event
                 (:state saved)
                 [:daemon-response nil
                  (merge {:request (:request saved)}
                         (:request-context saved))]))
        recovered (:state (ui/handle-event failed-request [:status status]))]
    (is (= "Daemon not responding." (:error failed-request)))
    (is (nil? (:error recovered)))
    (is (= :open (get-in recovered [:status :state])))
    (testing "validation errors survive unrelated healthy status polls"
      (let [state {:form (ui/schedule->form sched/empty-schedule)
                   :error "Blocks need at least nine hours between them."}
            recovered (:state
                       (ui/handle-event
                        state
                        [:status status]))]
        (is (= (:error state) (:error recovered)))))
    (testing "a stale uninstall connectivity panel also dismisses on recovery"
      (let [context {:uninstall-generation 1 :uninstall-token 2}
            failed
            (:state
             (ui/handle-event
              {:status status
               :form (ui/schedule->form sched/empty-schedule)
               :uninstall-generation 1
               :uninstall-token-seq 2
               :uninstall-request context}
              [:daemon-response nil
               (assoc context :request {:op :uninstall})]))
            recovered
            (:state
             (ui/handle-event
              failed
              [:status status]))]
        (is (= :uninstall-refused (get-in failed [:panel :kind])))
        (is (nil? (:panel recovered)))))))

(deftest stale-schedule-responses-are-ignored
  (let [initial (state-for (daemon-status :open))
        edited (:state (ui/handle-event initial [:toggle :mon true]))
        saved (ui/handle-event edited [:save])
        st (assoc (:state saved) :error "newer edit")
        form (:form st)
        stale-context (-> (:request-context saved)
                          (update :revision dec)
                          (assoc :request (:request saved)))
        result (ui/handle-event st [:daemon-response
                                    {:ok true :applied false :freezes-now []}
                                    stale-context])]
    (is (= st (:state result)))
    (is (nil? (:request result)))
    (let [applied (ui/handle-event st [:daemon-response
                                       {:ok true :applied true
                                        :status (daemon-status :open
                                                               (ui/form->schedule form))}
                                       stale-context])]
      (is (= st (:state applied))))))

(deftest notification-is-recorded-only-after-success
  (let [st {:notified #{}}
        result (:state (ui/handle-event st [:notification-posted 123]))]
    (is (= #{123} (:notified result)))))
