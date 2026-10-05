(ns gotosleep.agent.client
  "The agent's control-socket client. Reuses the daemon's AF_UNIX client.
  Parsing is defensive: any failure becomes :daemon-down."
  (:require [clojure.edn :as edn]
            [gotosleep.daemon.socket :as socket]))

(defn- parse [resp]
  (when resp
    (try (edn/read-string {:default (fn [_ _] nil)} resp)
         (catch Throwable _ nil))))

(defn poll-status
  "Send {:op :status} and return the parsed status map, or :daemon-down."
  [socket-path]
  (let [m (parse (try (socket/request! socket-path "{:op :status}") (catch Throwable _ nil)))]
    (if (and (map? m) (:ok m)) m :daemon-down)))

(defn send-request
  "Send an arbitrary request map and return the parsed response, or nil."
  [socket-path req]
  (parse (try (socket/request! socket-path (pr-str req)) (catch Throwable _ nil))))
