(ns gotosleep.agent.shell-test
  "Headless agent-shell tests: the control-socket client against an in-process
  server, and the notification argv. No window is opened."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [gotosleep.agent.client :as client]
            [gotosleep.agent.log :as agent-log]
            [gotosleep.agent.main :as main]
            [gotosleep.agent.notify :as notify]
            [gotosleep.agent.relock :as relock]
            [gotosleep.agent.ui :as ui]
            [gotosleep.daemon.socket :as socket]
            [gotosleep.protocol :as proto]
            [gotosleep.schedule :as sched]
            [gotosleep.tz :as tz]
            [jolt.process :as p]))

(defn tmp [] (str "/tmp/gts-agent-" (System/nanoTime) ".sock"))
(defn open-fd-count [] (count (.list (java.io.File. "/dev/fd"))))

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

(defn confirmed-uninstall-context!
  "Open and confirm a fresh uninstall panel, returning the exact shell queue
  context emitted by the reducer."
  [state-atom]
  (main/transition! state-atom [:open-uninstall])
  (let [{:keys [request request-context]} (main/transition!
                                           state-atom
                                           [:confirm-uninstall])]
    (merge {:request request} request-context)))

(defn emitted-context
  [{:keys [request request-context]}]
  (merge {:request request} request-context))

(defn initialized-state [status]
  (let [st (atom {:form nil
                  :form-dirty? false
                  :form-revision 0
                  :schedule-generation 0
                  :schedule-action-seq 0
                  :uninstall-generation 0
                  :uninstall-token-seq 0})]
    (main/transition! st [:status status])
    st))

(defn wait-until [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn with-server [respond f]
  (let [path (tmp)
        fd (socket/bind-listen! path)
        stop (atom false)
        server (future (while (not @stop) (socket/serve-one! fd (fn [b uid] (respond b uid)) 50)))]
    (try (f path)
         (finally (reset! stop true) (Thread/sleep 100) (socket/close! fd path)))))

(deftest poll-status-parses-ok-response
  (with-server
    (fn [_ _] (.getBytes (str (pr-str {:ok true :state :frozen :now 123 :zone "UTC"}) "\n") "UTF-8"))
    (fn [path]
      (let [st (client/poll-status path)]
        (is (map? st))
        (is (= :frozen (:state st)))
        (is (= 123 (:now st)))))))

(deftest poll-status-daemon-down
  (testing "no socket at all"
    (is (= :daemon-down (client/poll-status (tmp)))))
  (testing "a non-ok / garbage response"
    (with-server (fn [_ _] (.getBytes "garbage )(\n" "UTF-8"))
      (fn [path] (is (= :daemon-down (client/poll-status path)))))
    (with-server (fn [_ _] (.getBytes (str (pr-str {:ok false}) "\n") "UTF-8"))
      (fn [path] (is (= :daemon-down (client/poll-status path)))))))

(deftest send-request-roundtrip
  (with-server
    (fn [_ _] (.getBytes (str (pr-str {:ok true :applied true}) "\n") "UTF-8"))
    (fn [path]
      (is (= {:ok true :applied true}
             (client/send-request path {:op :set-schedule :schedule {} :dry-run true}))))))

(deftest notify-argv-shape
  (let [argv (notify/notify-argv "Go To Sleep" "Bedtime at 23:00 — the screen locks in 5 minutes.")]
    (is (= "/usr/bin/osascript" (first argv)))
    (is (= "-e" (second argv)))
    (is (str/starts-with? (nth argv 2) "display notification "))
    (is (str/includes? (nth argv 2) "with title \"Go To Sleep\""))))

(deftest notification-launch-result-is-explicit
  (with-redefs [p/process (fn [& _] (throw (ex-info "spawn failed" {})))]
    (is (false? (notify/post! "t" "b"))))
  (with-redefs [p/process (fn [& _] :process)]
    (is (true? (notify/post! "t" "b")))))

(deftest notification-processes-are-reaped-without-pipe-leaks
  (let [before (open-fd-count)]
    (with-redefs [notify/notify-argv (fn [& _] ["/usr/bin/true"])]
      (is (every? true? (repeatedly 30 #(notify/post! "t" "b")))))
    (is (wait-until #(<= (open-fd-count) (+ before 3)))
        "notification children must close their stdin/stdout/stderr pipes"))
  (testing "waiting for osascript happens off the poller thread"
    (let [proc (promise)
          started (System/currentTimeMillis)]
      (with-redefs [p/process (fn [& _] proc)]
        (is (true? (notify/post! "t" "b")))
        (is (< (- (System/currentTimeMillis) started) 100))
        (deliver proc {:exit 0 :out "" :err ""})))))

(deftest transition-retries-after-a-concurrent-state-change
  (let [st (atom {:base true})
        entered (promise)
        release (promise)
        calls (atom 0)
        reducer (fn [s _]
                  (when (= 1 (swap! calls inc))
                    (deliver entered true)
                    @release)
                  {:state (assoc s :response true)})
        worker (future (main/transition-with! st reducer [:response]))]
    @entered
    (swap! st assoc :user-edit true)
    (deliver release true)
    @worker
    (is (= {:base true :user-edit true :response true} @st))
    (is (= 2 @calls))))

(deftest request-chain-executes-the-real-save
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        save (main/transition! st [:save])
        context (emitted-context save)
        candidate (get-in save [:request :schedule])
        sent (atom [])
        send-fn (fn [req]
                  (swap! sent conj req)
                  (if (:dry-run req)
                    {:ok true :applied false :edit-mode :open
                     :freezes-now []}
                    {:ok true :applied true :edit-mode :open
                     :status (daemon-status :open candidate)}))]
    (main/process-request-chain! st send-fn context)
    (is (= 2 (count @sent)))
    (is (true? (:dry-run (first @sent))))
    (is (nil? (:dry-run (second @sent))))
    (is (= :open (:edit-mode (first @sent))))
    (is (false? (:form-dirty? @st)))
    (is (= candidate (:baseline-schedule @st)))))

(deftest immediate-freeze-confirmation-is-protocol-valid-and-applies-once
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        save (main/transition! st [:save])
        save-context (emitted-context save)
        candidate (get-in save [:request :schedule])
        wire-occ (assoc (proto/occ->wire (tz/load-zone "UTC") 0
                                         {:day :mon :start 1 :end 2})
                        :ignored "never echo this")
        signature (select-keys wire-occ [:day :start :end])
        sent (atom [])
        decoded (atom [])
        send-fn (fn [req]
                  (swap! sent conj req)
                  (swap! decoded conj
                         (proto/read-request (.getBytes (pr-str req) "UTF-8")))
                  (if (:dry-run req)
                    {:ok true :applied false :edit-mode :open
                     :freezes-now [wire-occ]
                     :confirm-until-label "Mon 00:00"}
                    {:ok true :applied true :edit-mode :open
                     :status (daemon-status :open candidate)}))]
    (main/process-request-chain! st send-fn save-context)
    (is (= :confirm-freeze (get-in @st [:panel :kind])))
    (is (= [signature] (get-in @st [:panel :freezes-now])))
    (is (= 1 (count @sent)))
    (let [confirm (main/transition! st [:confirm-freeze])
          confirm-context (emitted-context confirm)]
      (is (= [signature] (get-in confirm [:request :confirm-freeze])))
      (main/process-request-chain! st send-fn confirm-context))
    (is (= 2 (count @sent)))
    (is (every? #(= :ok (first %)) @decoded))
    (is (= [signature] (get-in (second @sent) [:confirm-freeze])))
    (is (nil? (get-in (second @sent) [:confirm-freeze 0 :ignored])))
    (is (false? (:form-dirty? @st)))
    (is (= candidate (:baseline-schedule @st)))))

(deftest uninstall-success-does-not-create-a-save-request
  (let [st (atom {:status (daemon-status :open)})
        sent (atom [])
        context (confirmed-uninstall-context! st)]
    (main/process-request-chain!
     st
     (fn [req] (swap! sent conj req) {:ok true})
     context)
    (is (= [{:op :uninstall}] @sent))
    (is (nil? (:uninstall-request @st)))))

(deftest queued-uninstall-requires-current-open-token-before-io
  (let [source (atom {:status (daemon-status :open)})
        context (confirmed-uninstall-context! source)
        current @source
        malformed (dissoc (daemon-status :open) :version)
        cases [[:startup (assoc current :status nil) context]
               [:daemon-down (assoc current :status :daemon-down) context]
               [:malformed (assoc current :status malformed) context]
               [:frozen (assoc current :status (daemon-status :frozen)) context]
               [:active (assoc current :status (daemon-status :active)) context]
               [:missing-request (assoc current :uninstall-request nil) context]
               [:stale-generation current
                (update context :uninstall-generation inc)]
               [:stale-token current (update context :uninstall-token inc)]
               [:missing-generation current
                (dissoc context :uninstall-generation)]
               [:missing-token current (dissoc context :uninstall-token)]]]
    (doseq [[label state request-context] cases]
      (let [sent (atom [])]
        (main/process-request-chain!
         (atom state)
         (fn [req] (swap! sent conj req) {:ok true})
         request-context)
        (is (empty? @sent) (name label))))))

(deftest uninstall-is-rechecked-after-its-token-is-claimed
  (let [st (atom {:status (daemon-status :open)})
        context (confirmed-uninstall-context! st)
        checks (atom 0)
        sent (atom [])]
    (with-redefs [ui/uninstall-request-current?
                  (fn [_ _] (= 1 (swap! checks inc)))]
      (main/process-request-chain!
       st
       (fn [req] (swap! sent conj req) {:ok true})
       context))
    (is (= 2 @checks))
    (is (empty? @sent))))

(deftest shell-carries-uninstall-authority-and-shows-only-accepted-panels
  (testing "a synthetic locked-state open is inert"
    (let [st (atom {:status (daemon-status :frozen)})
          shown (atom 0)
          submitted (atom [])]
      (main/apply-action-with!
       st #(swap! submitted conj %) #(swap! shown inc) [:open-uninstall])
      (is (zero? @shown))
      (is (empty? @submitted))
      (is (nil? (:panel @st)))))
  (testing "a fresh open panel is shown and its exact token is submitted once"
    (let [st (atom {:status (daemon-status :open)})
          shown (atom 0)
          submitted (atom [])]
      (main/apply-action-with!
       st #(swap! submitted conj %) #(swap! shown inc) [:open-uninstall])
      (is (= 1 @shown))
      (is (= :uninstall (get-in @st [:panel :kind])))
      (let [panel-context (select-keys (:panel @st)
                                       [:uninstall-generation :uninstall-token])]
        (main/apply-action-with!
         st #(swap! submitted conj %) #(swap! shown inc) [:confirm-uninstall])
        (main/apply-action-with!
         st #(swap! submitted conj %) #(swap! shown inc) [:confirm-uninstall])
        (is (= [{:request {:op :uninstall}
                 :uninstall-generation (:uninstall-generation panel-context)
                 :uninstall-token (:uninstall-token panel-context)}]
               @submitted))
        (is (= panel-context (:uninstall-request @st)))
        (is (= 1 @shown))))))

(deftest request-runner-preserves-submission-order
  (let [st (atom {})
        calls (atom [])
        first-started (promise)
        release-first (promise)
        send-fn (fn [req]
                  (swap! calls conj (:test-id req))
                  (when (= 1 (:test-id req))
                    (deliver first-started true)
                    @release-first)
                  {:ok true})
        runner (main/make-request-runner st send-fn)]
    (main/submit-request! runner {:request {:op :test :test-id 1}})
    @first-started
    (main/submit-request! runner {:request {:op :test :test-id 2}})
    (deliver release-first true)
    (is (wait-until #(not @(:running? runner))))
    (is (= [1 2] @calls))))

(deftest duplicate-and-stale-queued-uninstalls-never-escape-their-open-epoch
  (testing "duplicate queue entries send at most one uninstall"
    (let [st (atom {:status (daemon-status :open)})
          context (confirmed-uninstall-context! st)
          calls (atom [])
          blocker-started (promise)
          release-blocker (promise)
          runner (main/make-request-runner
                  st
                  (fn [req]
                    (if (= :block (:test-id req))
                      (do
                        (swap! calls conj :block)
                        (deliver blocker-started true)
                        @release-blocker
                        {:ok true})
                      (do
                        (swap! calls conj :uninstall)
                        {:ok true}))))]
      (main/submit-request! runner {:request {:op :test :test-id :block}})
      @blocker-started
      (main/submit-request! runner context)
      (main/submit-request! runner context)
      (deliver release-blocker true)
      (is (wait-until #(not @(:running? runner))))
      (is (= [:block :uninstall] @calls))))
  (doseq [[label statuses]
          [[:frozen [(daemon-status :frozen)]]
           [:active [(daemon-status :active)]]
           [:down [:daemon-down]]
           [:malformed [(dissoc (daemon-status :open) :version)]]
           [:frozen-then-open [(daemon-status :frozen)
                               (daemon-status :open)]]
           [:down-then-open [:daemon-down (daemon-status :open)]]]]
    (testing (name label)
      (let [st (atom {:status (daemon-status :open)})
            context (confirmed-uninstall-context! st)
            calls (atom [])
            blocker-started (promise)
            release-blocker (promise)
            runner (main/make-request-runner
                    st
                    (fn [req]
                      (swap! calls conj (or (:test-id req) :uninstall))
                      (when (= :block (:test-id req))
                        (deliver blocker-started true)
                        @release-blocker)
                      {:ok true}))]
        (main/submit-request! runner {:request {:op :test :test-id :block}})
        @blocker-started
        (main/submit-request! runner context)
        (doseq [status statuses]
          (main/transition! st [:status status]))
        (deliver release-blocker true)
        (is (wait-until #(not @(:running? runner))))
        (is (= [:block] @calls))))))

(deftest status-poll-cannot-interleave-inside-a-request-chain
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        save (main/transition! st [:save])
        context (emitted-context save)
        candidate (get-in save [:request :schedule])
        calls (atom [])
        dry-run-started (promise)
        release-dry-run (promise)
        socket-gate (Object.)
        send-fn (fn [req]
                  (if (:dry-run req)
                    (do
                      (swap! calls conj :dry-run)
                      (deliver dry-run-started true)
                      @release-dry-run
                      {:ok true :applied false :edit-mode :open
                       :freezes-now []})
                    (do
                      (swap! calls conj :apply)
                      {:ok true :applied true :edit-mode :open
                       :status (daemon-status :open candidate)})))
        runner (main/make-request-runner st send-fn socket-gate)
        request (:request context)]
    (main/submit-request! runner context)
    @dry-run-started
    (let [poll (future
                 (main/with-socket-gate!
                  socket-gate
                  #(swap! calls conj :status)))]
      (Thread/sleep 20)
      (is (= [:dry-run] @calls))
      (deliver release-dry-run true)
      (is (wait-until #(not @(:running? runner))))
      @poll
      (is (= [:dry-run :apply :status] @calls))
      (is (false? (:form-dirty? @st)))
      (is (= candidate (:baseline-schedule @st)))
      (is (nil? (:error @st))))))

(deftest queued-schedule-requests-require-exact-authority-context
  (let [source (initialized-state (daemon-status :open))
        _ (main/transition! source [:toggle :mon true])
        context (emitted-context (main/transition! source [:save]))
        current @source
        cases [[:startup (assoc current :status nil) context]
               [:down (assoc current :status :daemon-down) context]
               [:malformed (assoc current :status
                                  (dissoc (daemon-status :open) :version))
                context]
               [:growth-mode (assoc current :status (daemon-status :frozen))
                context]
               [:stale-generation current (update context :generation inc)]
               [:stale-revision current (update context :revision inc)]
               [:stale-mode current (assoc context :edit-mode :growth-only)]
               [:stale-action current (update context :action-token dec)]
               [:missing-action current (dissoc context :action-token)]]]
    (doseq [[label state queued] cases]
      (let [sent (atom [])]
        (main/process-request-chain!
         (atom state)
         (fn [req] (swap! sent conj req) {:ok true})
         queued)
        (is (empty? @sent) (name label))))
    (let [sent (atom [])]
      (main/process-request-chain!
       (atom current)
       (fn [req]
         (swap! sent conj req)
         {:ok false :error {:code :window :message "stop"}})
       context)
      (is (= [(:request context)] @sent)))))

(deftest schedule-action-claims-and-authority-drop-duplicates
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        calls (atom [])
        blocker-started (promise)
        release-blocker (promise)
        send-fn (fn [req]
                  (cond
                    (= :block (:test-id req))
                    (do (swap! calls conj :block)
                        (deliver blocker-started true)
                        @release-blocker
                        {:ok true})

                    (:dry-run req)
                    (do (swap! calls conj :dry-run)
                        {:ok true :applied false :edit-mode :open
                         :freezes-now []})

                    :else
                    (do (swap! calls conj :apply)
                        {:ok true :applied true :edit-mode :open
                         :status (daemon-status :open (:schedule req))})))
        runner (main/make-request-runner st send-fn)]
    (main/submit-request! runner {:request {:op :test :test-id :block}})
    @blocker-started
    (let [save-one (main/transition! st [:save])
          context-one (emitted-context save-one)
          save-two (main/transition! st [:save])
          context-two (emitted-context save-two)]
      (is (not= (:action-token context-one) (:action-token context-two)))
      (main/submit-request! runner context-one)
      (main/submit-request! runner context-one)
      (main/submit-request! runner context-two)
      (deliver release-blocker true)
      (is (wait-until #(not @(:running? runner))))
      (is (= [:block :dry-run :apply] @calls))
      (is (false? (:form-dirty? @st))))))

(deftest schedule-action-claim-is-atomic-and-rechecked-before-io
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        context (emitted-context (main/transition! st [:save]))
        claimed-uninstalls (atom #{})
        claimed-schedules (atom #{})
        sent (atom 0)
        send-fn (fn [_]
                  (swap! sent inc)
                  {:ok false :error {:code :window :message "stop"}})
        workers (doall
                 (repeatedly
                  2
                  #(future
                     (main/process-request-chain!
                      st send-fn context
                      claimed-uninstalls claimed-schedules))))]
    (doseq [worker workers] @worker)
    (is (= 1 @sent)))
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        context (emitted-context (main/transition! st [:save]))
        checks (atom 0)
        sent (atom [])]
    (with-redefs [ui/schedule-request-current?
                  (fn [_ _] (= 1 (swap! checks inc)))]
      (main/process-request-chain!
       st
       (fn [request] (swap! sent conj request) {:ok true})
       context))
    (is (= 2 @checks))
    (is (empty? @sent))))

(deftest growth-confirm-is-a-separate-one-shot-chain
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)
        st (initialized-state status)
        _ (main/transition! st [:edit-end :mon "08:00"])
        save (main/transition! st [:save])
        candidate (get-in save [:request :schedule])
        token {:baseline baseline
               :candidate candidate
               :zone "UTC"
               :authority-frozen (ui/canonical-frozen-signatures status)
               :authority-unfrozen-at (:unfrozen-at status)
               :confirm-until-at 3000
               :activates-now false}
        _ (main/transition!
           st
           [:daemon-response
            {:ok true :applied false :edit-mode :growth-only
             :confirm-growth token
             :confirm-until-label "Mon 08:00"
             :activates-now false}
            (emitted-context save)])
        confirm (main/transition! st [:confirm-growth])
        context (emitted-context confirm)
        calls (atom [])
        blocker-started (promise)
        release-blocker (promise)
        send-fn (fn [req]
                  (if (= :block (:test-id req))
                    (do (swap! calls conj :block)
                        (deliver blocker-started true)
                        @release-blocker
                        {:ok true})
                    (do (swap! calls conj :confirm)
                        {:ok true :applied true :edit-mode :growth-only
                         :status (daemon-status :active candidate)})))
        runner (main/make-request-runner st send-fn)]
    (main/submit-request! runner {:request {:op :test :test-id :block}})
    @blocker-started
    (main/submit-request! runner context)
    (main/submit-request! runner context)
    (deliver release-blocker true)
    (is (wait-until #(not @(:running? runner))))
    (is (= [:block :confirm] @calls))
    (is (false? (:form-dirty? @st)))
    (is (= candidate (:baseline-schedule @st)))))

(deftest authority-change-during-open-dry-run-prevents-apply-follow-up
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        save (main/transition! st [:save])
        context (emitted-context save)
        sent (atom [])
        dry-run-started (promise)
        release-dry-run (promise)
        send-fn (fn [req]
                  (swap! sent conj req)
                  (deliver dry-run-started true)
                  @release-dry-run
                  {:ok true :applied false :edit-mode :open
                   :freezes-now []})
        worker (future
                 (main/process-request-chain! st send-fn context))]
    @dry-run-started
    (main/transition! st [:status (daemon-status :frozen)])
    (deliver release-dry-run true)
    @worker
    (is (= [(:request context)] @sent))
    (is (= :frozen (get-in @st [:status :state])))
    (is (nil? (:panel @st)))
    (is (nil? (:error @st)))))

(deftest growth-request-is-dropped-across-open-down-and-malformed-transitions
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        original (daemon-status :active baseline)]
    (doseq [[label next-status]
            [[:open (daemon-status :open baseline)]
             [:down :daemon-down]
             [:malformed (dissoc original :version)]]]
      (let [st (initialized-state original)
            _ (main/transition! st [:edit-end :mon "08:00"])
            context (emitted-context (main/transition! st [:save]))
            sent (atom [])
            blocker-started (promise)
            release-blocker (promise)
            runner (main/make-request-runner
                    st
                    (fn [req]
                      (if (= :block (:test-id req))
                        (do (deliver blocker-started true)
                            @release-blocker
                            {:ok true})
                        (do (swap! sent conj req)
                            {:ok true :applied false
                             :edit-mode :growth-only}))))]
        (main/submit-request! runner {:request {:op :test :test-id :block}})
        @blocker-started
        (main/submit-request! runner context)
        (main/transition! st [:status next-status])
        (deliver release-blocker true)
        (is (wait-until #(not @(:running? runner))))
        (is (empty? @sent) (name label))
        (is (false? (:form-dirty? @st)) (name label))))))

(deftest growth-frozen-to-active-same-authority-preserves-response
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        frozen-status (daemon-status :frozen baseline)
        st (initialized-state frozen-status)
        _ (main/transition! st [:edit-start :mon "22:30"])
        save (main/transition! st [:save])
        context (emitted-context save)
        candidate (get-in save [:request :schedule])
        token {:baseline baseline
               :candidate candidate
               :zone "UTC"
               :authority-frozen (ui/canonical-frozen-signatures frozen-status)
               :authority-unfrozen-at (:unfrozen-at frozen-status)
               :confirm-until-at 3000
               :activates-now true}
        sent (atom [])
        active-status (assoc frozen-status
                             :state :active
                             :occurrences
                             (mapv #(assoc % :active? true)
                                   (:occurrences frozen-status)))]
    (main/transition! st [:status active-status])
    (main/process-request-chain!
     st
     (fn [req]
       (swap! sent conj req)
       {:ok true :applied false :edit-mode :growth-only
        :confirm-growth token
        :confirm-until-label "Mon 08:00"
        :activates-now true})
     context)
    (is (= [(:request context)] @sent))
    (is (= :confirm-growth (get-in @st [:panel :kind])))
    (is (= candidate (get-in @st [:panel :schedule])))))

(deftest local-edit-while-a-dry-run-is-pending-invalidates-its-response
  (let [st (initialized-state (daemon-status :open))
        _ (main/transition! st [:toggle :mon true])
        save (main/transition! st [:save])
        context (emitted-context save)
        sent (atom [])
        dry-run-started (promise)
        release-dry-run (promise)
        worker (future
                 (main/process-request-chain!
                  st
                  (fn [req]
                    (swap! sent conj req)
                    (deliver dry-run-started true)
                    @release-dry-run
                    {:ok true :applied false :edit-mode :open
                     :freezes-now []})
                  context))]
    @dry-run-started
    (main/transition! st [:edit-start :mon "21:45"])
    (deliver release-dry-run true)
    @worker
    (is (= [(:request context)] @sent))
    (is (= "21:45" (get-in @st [:form :mon :start])))
    (is (:form-dirty? @st))
    (is (nil? (:panel @st)))))

(deftest post-apply-status-supports-a-second-action-before-poll
  (let [baseline (assoc sched/empty-schedule
                        :mon {:start "23:00" :end "07:00"})
        status (daemon-status :active baseline)
        st (initialized-state status)
        _ (main/transition! st [:edit-start :mon "22:30"])
        first-save (main/transition! st [:save])
        candidate (get-in first-save [:request :schedule])
        token {:baseline baseline
               :candidate candidate
               :zone "UTC"
               :authority-frozen (ui/canonical-frozen-signatures status)
               :authority-unfrozen-at (:unfrozen-at status)
               :confirm-until-at 3000
               :activates-now false}
        _ (main/transition!
           st
           [:daemon-response
            {:ok true :applied false :edit-mode :growth-only
             :confirm-growth token :confirm-until-label "Mon 07:00"
             :activates-now false}
            (emitted-context first-save)])
        confirm (main/transition! st [:confirm-growth])
        _ (main/process-request-chain!
           st
           (fn [_]
             {:ok true :applied true :edit-mode :growth-only
              :status (daemon-status :active candidate)})
           (emitted-context confirm))
        _ (main/transition! st [:edit-end :mon "08:00"])
        second-save (main/transition! st [:save])]
    (is (= candidate (:baseline-schedule @st)))
    (is (= "08:00" (get-in second-save [:request :schedule :mon :end])))
    (is (= :growth-only (get-in second-save [:request :edit-mode])))
    (is (= (:schedule-generation @st)
           (get-in second-save [:request-context :generation])))))

(defn warning-status []
  (daemon-status
   :frozen sched/empty-schedule
   {:now 100000
    :unfrozen-label "Mon 07:00"
    :occurrences [{:day :mon :start 340000 :end 400000
                   :frozen-from (- 340000 (* 8 60 60 1000))
                   :active? false :frozen? true :today? true
                   :label "Mon 23:00–07:00"}]}))

(defn poll-deps [st status notify-fn logs]
  {:state-atom st
   :poll-status-fn (fn [] status)
   :notify-fn notify-fn
   :session-fn (fn [] nil)
   :lock-fn (fn [] (throw (ex-info "must not lock" {})))
   :mono-ms-fn (fn [] 0)
   :wall-ms-fn (fn [] 1000)
   :log-fn (fn [event data] (swap! logs conj [event data]))})

(deftest failed-notification-launch-is-retried
  (let [st (atom {:notified #{} :form-dirty? false :form-revision 0})
        attempts (atom 0)
        logs (atom [])
        notify-fn (fn [_ _] (= 2 (swap! attempts inc)))
        deps (poll-deps st (warning-status) notify-fn logs)]
    (main/poll-once-with! deps)
    (is (= #{} (:notified @st)))
    (main/poll-once-with! deps)
    (is (= #{340000} (:notified @st)))
    (is (= [:warning-launch-failed :warning-launched] (mapv first @logs)))))

(deftest secondary-relock-is-local-qualified-and-rate-limited
  (let [st (atom {:notified #{} :form-dirty? false :form-revision 0})
        mono-values (atom [1000 1500 2000])
        locks (atom 0)
        logs (atom [])
        status (daemon-status
                :active sched/empty-schedule
                {:now 100000
                 :unfrozen-label "Mon 07:00"
                 :occurrences [{:day :mon :start 90000 :end 200000
                                :frozen-from (- 90000 (* 8 60 60 1000))
                                :active? true :frozen? true :today? true
                                :label "Mon 23:00–07:00"}]})
        deps {:state-atom st
              :poll-status-fn (fn [] status)
              :notify-fn (fn [& _] (throw (ex-info "must not notify" {})))
              :session-fn (fn [] {"kCGSSessionOnConsoleKey" true
                                  "CGSSessionScreenIsLocked" false})
              :lock-fn (fn [] (swap! locks inc) 0)
              :mono-ms-fn (fn [] (let [v (first @mono-values)]
                                    (swap! mono-values subvec 1)
                                    v))
              :wall-ms-fn (fn [] 1000)
              :log-fn (fn [event data] (swap! logs conj [event data]))}]
    (main/poll-once-with! deps)
    (main/poll-once-with! deps)
    (main/poll-once-with! deps)
    (is (= 2 @locks))
    (is (= [[:relock-call {:rc 0}] [:relock-call {:rc 0}]] @logs))))

(deftest secondary-relock-honors-daemon-safety-stop
  (let [st (atom {:notified #{} :form-dirty? false :form-revision 0})
        session-reads (atom 0)
        locks (atom 0)
        status (daemon-status
                :active sched/empty-schedule
                {:now 100000
                 :safety-stopped true
                 :unfrozen-label "Mon 07:00"
                 :occurrences [{:day :mon :start 90000 :end 200000
                                :frozen-from (- 90000 (* 8 60 60 1000))
                                :active? true :frozen? true :today? true
                                :label "Mon 23:00–07:00"}]})
        deps {:state-atom st
              :poll-status-fn (fn [] status)
              :notify-fn (fn [& _] (throw (ex-info "must not notify" {})))
              :session-fn (fn [] (swap! session-reads inc)
                            {"kCGSSessionOnConsoleKey" true
                             "CGSSessionScreenIsLocked" false})
              :lock-fn (fn [] (swap! locks inc) 0)
              :mono-ms-fn (fn [] 1000)
              :wall-ms-fn (fn [] 1000)
              :log-fn (fn [& _])}]
    (main/poll-once-with! deps)
    (is (zero? @session-reads))
    (is (zero? @locks))))

(deftest daemon-down-periods-log-only-transitions
  (let [st (atom {:status {:state :open} :form-dirty? false})
        statuses (atom [:daemon-down :daemon-down (daemon-status :open)])
        walls (atom [100 150 250])
        logs (atom [])
        deps {:state-atom st
              :poll-status-fn (fn [] (let [v (first @statuses)] (swap! statuses subvec 1) v))
              :notify-fn (fn [& _] true)
              :session-fn (fn [] nil)
              :lock-fn (fn [] nil)
              :mono-ms-fn (fn [] 0)
              :wall-ms-fn (fn [] (let [v (first @walls)] (swap! walls subvec 1) v))
              :log-fn (fn [event data] (swap! logs conj [event data]))}]
    (dotimes [_ 3] (main/poll-once-with! deps))
    (is (= [[:daemon-down-begin {}]
            [:daemon-down-end {:duration-ms 150}]] @logs))))

(deftest agent-log-has-millisecond-prefix
  (let [path (str "/tmp/gts-agent-log-" (System/nanoTime) ".log")]
    (try
      (is (agent-log/append-at! path 1720000000123 :warning-launched {:start 1}))
      (is (= "1720000000123 warning-launched {:start 1}\n" (slurp path)))
      (finally (.delete (java.io.File. path))))))

(deftest relock-session-classifier-fails-closed
  (is (relock/session-unlocked-on-console?
       {"kCGSSessionOnConsoleKey" true "CGSSessionScreenIsLocked" false}))
  (is (relock/session-unlocked-on-console?
       {"kCGSSessionOnConsoleKey" true}))
  (is (not (relock/session-unlocked-on-console?
            {"kCGSSessionOnConsoleKey" true "CGSSessionScreenIsLocked" true})))
  (is (not (relock/session-unlocked-on-console?
            {"kCGSSessionOnConsoleKey" 1 "CGSSessionScreenIsLocked" false})))
  (is (not (relock/session-unlocked-on-console?
            {"kCGSSessionOnConsoleKey" true "CGSSessionScreenIsLocked" 0}))))

(deftest alpha-zero-lifecycle-is-ordered
  (let [calls (atom [])
        scheduled (atom nil)]
    (main/hide-window-at-launch!
     :window
     (fn [w alpha] (swap! calls conj [:alpha w alpha]))
     (fn [f] (swap! calls conj [:schedule]) (reset! scheduled f))
     (fn [w] (swap! calls conj [:hide w])))
    (is (= [[:alpha :window 0.0] [:schedule]] @calls))
    (@scheduled)
    (is (= [[:alpha :window 0.0] [:schedule] [:hide :window]] @calls))
    (reset! calls [])
    (main/show-window-with!
     :window
     #(swap! calls conj [:activate])
     (fn [w alpha] (swap! calls conj [:alpha w alpha]))
     (fn [w] (swap! calls conj [:show w]))
     (fn [w] (swap! calls conj [:front w])))
    (is (= [[:activate] [:alpha :window 1.0] [:show :window] [:front :window]] @calls))))

(deftest application-reopen-brings-settings-forward
  (let [calls (atom [])]
    (is (= (char 1)
           (main/handle-reopen-with! #(swap! calls conj :show))))
    (is (= [:show] @calls))))

(deftest native-menu-caller-ownership-is-balanced
  (let [calls (atom [])]
    (is (= [:one :two]
           (main/install-owned-menu!
            :status :menu [:one :two]
            (fn [menu item] (swap! calls conj [:add menu item]))
            (fn [status menu] (swap! calls conj [:set status menu]))
            (fn [obj] (swap! calls conj [:release obj])))))
    (is (= [[:add :menu :one] [:add :menu :two] [:set :status :menu]
            [:release :one] [:release :two] [:release :menu]]
           @calls)))
  (testing "ownership is still balanced if installation throws"
    (let [released (atom [])]
      (is (thrown? Throwable
                   (main/install-owned-menu!
                    :status :menu [:one :two]
                    (fn [_ _] (throw (ex-info "add failed" {})))
                    (fn [& _] nil)
                    #(swap! released conj %))))
      (is (= [:one :two :menu] @released)))))
