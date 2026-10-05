(ns gotosleep.daemon.jobs
  "Non-blocking daemon jobs. Maps effects to argv and timeouts, spawns via
  jolt.process without waiting, polls for completion, and destroys anything
  past its timeout. At most one job per key is in flight."
  (:require [gotosleep.subprocess :as subprocess]
            [jolt.process :as p]))

(def agent-label "com.rubberducking.gotosleep.agent")

(def default-config
  {:lock-helper "/Library/Application Support/GoToSleep/bin/gotosleep-lock"
   :agent-plist "/Library/LaunchAgents/com.rubberducking.gotosleep.agent.plist"
   :agent-label agent-label
   :ntp-server "time.apple.com"})

(def timeouts
  {:lock 3000 :disablesleep 5000 :logout 10000 :sntp 10000 :restore-sleep 5000
   :agent-print 5000 :agent-bootstrap 10000 :agent-kickstart 5000})

(defn- key-kind [key] (if (vector? key) (first key) key))

(defn effect->argv
  "Return the argv for a `[:job key params]` effect."
  [key params {:keys [lock-helper agent-plist agent-label ntp-server]}]
  (let [uid (:uid params)
        svc (fn [u] (str "gui/" u "/" agent-label))]
    (case (key-kind key)
      :lock ["/bin/launchctl" "asuser" (str uid) lock-helper]
      :disablesleep ["/usr/bin/pmset" "-a" "disablesleep" "0"]
      :logout ["/bin/launchctl" "bootout" (str "gui/" uid)]
      :restore-sleep ["/usr/bin/pmset" "-a" "disablesleep" "1"]
      :sntp ["/usr/bin/sntp" "-t" "5" ntp-server]
      :agent-print ["/bin/launchctl" "print" (svc uid)]
      :agent-kickstart ["/bin/launchctl" "kickstart" (svc uid)]
      :agent-bootstrap ["/bin/sh" "-c"
                        (str "/bin/launchctl enable " (svc uid)
                             "; /bin/launchctl bootstrap gui/" uid " '" agent-plist "'")])))

(defn timeout-ms [key] (get timeouts (key-kind key) 5000))

;; --- the runner ------------------------------------------------------------
;; in-flight is {key {:proc <process> :argv [...] :started-ns ns}}.

(defn spawn
  "Spawn a job unless one with the same key is already in flight. Returns the
  updated in-flight map. A spawn IOException is turned into an immediate
  failed result on the next poll via a sentinel entry."
  [in-flight key params config now-ns]
  (if (contains? in-flight key)
    in-flight
    (let [argv (effect->argv key params config)]
      (try
        (let [proc (p/process argv {:out :string :err :string})]
          ;; No daemon job accepts stdin. Closing it immediately avoids holding
          ;; one descriptor for the duration of every in-flight child.
          (subprocess/close-input! proc)
          (assoc in-flight key {:proc proc :argv argv :started-ns now-ns}))
        (catch Throwable e
          (assoc in-flight key {:failed {:key key :exit -1 :out "" :err (str (ex-message e))
                                         :timed-out false} :started-ns now-ns}))))))

(defn poll
  "Collect results for finished or timed-out jobs. Returns [results in-flight'],
  where results is [{:key :exit :out :err :timed-out} …]. `now-ns` is a
  monotonic reading and drives timeouts."
  [in-flight now-ns]
  (reduce
   (fn [[results remaining] [key {:keys [proc failed argv started-ns] :as entry}]]
     (try
       (cond
         failed [(conj results failed) remaining]

         (not (p/alive? proc))
         (let [r (deref proc 50 :timeout)]
           (if (= :timeout r)
             [results (assoc remaining key entry)]
             (do
               (subprocess/close-pipes! proc)
               [(conj results {:key key :exit (:exit r) :out (:out r) :err (:err r)
                               :timed-out false})
                remaining])))

         (>= (- now-ns started-ns) (* 1000000 (timeout-ms key)))
         (do (try (p/destroy proc) (catch Throwable _ nil))
             (subprocess/close-pipes! proc)
             [(conj results {:key key :exit -1 :out "" :err "timeout" :timed-out true}) remaining])

         :else [results (assoc remaining key {:proc proc :argv argv :started-ns started-ns})])
       (catch Throwable ex
         (try (p/destroy proc) (catch Throwable _ nil))
         (subprocess/close-pipes! proc)
         [(conj results {:key key :exit -1 :out "" :err (str (ex-message ex))
                         :timed-out false})
          remaining])))
   [[] {}]
   in-flight))
