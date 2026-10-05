(ns gotosleep.daemon.socket
  "The AF_UNIX control socket. One connection is served per call, with
  SO_NOSIGPIPE, a 500 ms request-read deadline, and a fresh 2 s response-write
  deadline after handler completion. Enforcement never depends on it."
  (:require [jolt.ffi :as ffi]))

(ffi/load-library)
(ffi/defcfn c-socket "socket" [:int :int :int] :int {:capture-native-error true})
(ffi/defcfn c-bind "bind" [:int :pointer :uint] :int {:capture-native-error true})
(ffi/defcfn c-listen "listen" [:int :int] :int {:capture-native-error true})
(ffi/defcfn c-accept "accept" [:int :pointer :pointer] :int {:blocking true :capture-native-error true})
(ffi/defcfn c-connect "connect" [:int :pointer :uint] :int {:blocking true :capture-native-error true})
(ffi/defcfn c-poll "poll" [:pointer :uint :int] :int {:blocking true :capture-native-error true})
(ffi/defcfn c-read "read" [:int :pointer :size_t] :ssize_t {:blocking true :capture-native-error true})
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true :capture-native-error true})
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-unlink "unlink" [:string] :int)
(ffi/defcfn c-chmod "chmod" [:string :int] :int {:capture-native-error true})
(ffi/defcfn c-setsockopt "setsockopt" [:int :int :int :pointer :uint] :int {:capture-native-error true})
(ffi/defcfn c-getsockopt "getsockopt" [:int :int :int :pointer :pointer] :int
            {:capture-native-error true})
(ffi/defcfn c-getpeereid "getpeereid" [:int :pointer :pointer] :int {:capture-native-error true})
(ffi/defcfn c-fcntl "fcntl" [:int :int :& :int] :int {:capture-native-error true})

(def ^:private AF_UNIX 1)
(def ^:private SOCK_STREAM 1)
(def ^:private SOL_SOCKET 0xffff)
(def ^:private SO_NOSIGPIPE 0x1022)
(def ^:private SO_ERROR 0x1007)
(def ^:private POLLIN 1)
(def ^:private POLLOUT 4)
(def ^:private POLLERR 8)
(def ^:private POLLHUP 16)
(def ^:private POLLNVAL 32)
(def ^:private F_GETFL 3)
(def ^:private F_SETFL 4)
(def ^:private O_NONBLOCK 4)
(def ^:private EINTR 4)
(def ^:private EAGAIN 35)
(def ^:private EINPROGRESS 36)
(def ^:private EALREADY 37)
(def ^:private EISCONN 56)
(def ^:private sun-len 106)
(def ^:private sun-path-max 103) ; 104 bytes including the terminating NUL
(def ^:private max-bytes 65536)
(def ^:private request-budget-ns (* 500 1000000))
(def ^:private response-budget-ns (* 2000 1000000))

(defn- path-bytes [path]
  (when-not (string? path)
    (throw (ex-info "socket path must be a string" {:path path})))
  (let [bs (.getBytes ^String path "UTF-8")]
    (when (or (zero? (alength bs)) (> (alength bs) sun-path-max))
      (throw (ex-info "socket path is too long" {:path path :bytes (alength bs)
                                                  :max-bytes sun-path-max})))
    bs))

(defn- write-sockaddr! [p bs]
  (ffi/write p :uint8 sun-len 0)
  (ffi/write p :uint8 AF_UNIX 1)
  (ffi/write-array (+ p 2) bs)
  p)

(defn- poll-events
  "Wait up to `timeout-ms` and return poll(2)'s revents, 0 on timeout or
  interruption, and nil on a fatal poll error."
  [fd events timeout-ms]
  (ffi/with-alloc [pfd 8]
    (ffi/write pfd :int fd 0)
    (ffi/write pfd :int16 events 4)
    (ffi/write pfd :int16 0 6)
    (let [[n e] (c-poll pfd 1 timeout-ms)]
      (cond
        (pos? n) (ffi/read pfd :int16 6)
        (zero? n) 0
        (= e EINTR) 0
        :else nil))))

(defn- poll-ready?
  "Wait up to `timeout-ms` for `events` on `fd`."
  [fd events timeout-ms]
  (when-let [revents (poll-events fd events timeout-ms)]
    (pos? (bit-and revents events))))

(defn- remaining-ms [deadline-ns]
  (let [remaining (- deadline-ns (System/nanoTime))]
    (when (pos? remaining)
      (max 1 (quot (+ remaining 999999) 1000000)))))

(defn- set-nonblocking! [fd]
  (let [[flags e] (c-fcntl fd F_GETFL 0)]
    (when (neg? flags) (throw (ex-info "fcntl get flags failed" {:errno e})))
    (let [[r e] (c-fcntl fd F_SETFL (bit-or flags O_NONBLOCK))]
      (when (neg? r) (throw (ex-info "fcntl nonblocking failed" {:errno e}))))))

(defn- retryable-io-error? [e]
  (or (= e EINTR) (= e EAGAIN)))

(defn- terminal-poll-event? [revents]
  (pos? (bit-and revents (bit-or POLLERR POLLHUP POLLNVAL))))

(defn- socket-error [fd]
  (ffi/with-alloc [err 4]
    (ffi/with-alloc [len 4]
      (ffi/write len :uint32 4 0)
      (let [[r _] (c-getsockopt fd SOL_SOCKET SO_ERROR err len)]
        (when (zero? r) (ffi/read err :int 0))))))

(defn- connect-before!
  "Connect an already non-blocking socket before `deadline-ns`."
  [fd addr deadline-ns]
  (loop []
    (when-let [remaining (remaining-ms deadline-ns)]
      (let [[r e] (c-connect fd addr sun-len)]
        (cond
          (zero? r) true
          (= e EISCONN) true
          (= e EINTR) (recur)
          (or (= e EINPROGRESS) (= e EALREADY))
          (let [revents (poll-events fd POLLOUT (min 100 remaining))]
            (cond
              (nil? revents) false
              (or (pos? (bit-and revents POLLOUT))
                  (terminal-poll-event? revents))
              (let [e' (socket-error fd)]
                (cond
                  (zero? (or e' -1)) true
                  (or (= e' EINPROGRESS) (= e' EALREADY)) (recur)
                  :else false))
              :else (recur)))
          :else false)))))

(defn bind-listen!
  "Create, bind, chmod 0666, and listen on the socket at `path`, unlinking any
  stale file first. Returns the listening fd, or throws."
  [path]
  (let [path-bs (path-bytes path)]
    (c-unlink path)
    (let [[fd e] (c-socket AF_UNIX SOCK_STREAM 0)]
    (when (neg? fd) (throw (ex-info "socket failed" {:errno e})))
      (try
        (ffi/with-alloc [addr sun-len]
          (let [[r e] (c-bind fd (write-sockaddr! addr path-bs) sun-len)]
            (when (neg? r) (throw (ex-info "bind failed" {:errno e :path path})))))
        (let [[r e] (c-chmod path 0666)]
          (when (neg? r) (throw (ex-info "chmod failed" {:errno e :path path}))))
        (let [[r e] (c-listen fd 16)]
          (when (neg? r) (throw (ex-info "listen failed" {:errno e}))))
        fd
        (catch Throwable ex
          (c-close fd)
          (c-unlink path)
          (throw ex))))))

(defn- set-nosigpipe [fd]
  (ffi/with-alloc [one 4] (ffi/write one :int 1 0) (c-setsockopt fd SOL_SOCKET SO_NOSIGPIPE one 4)))

(defn- peer-uid [fd]
  (ffi/with-alloc [uid 4]
    (ffi/with-alloc [gid 4]
      (let [[r _] (c-getpeereid fd uid gid)]
        (when (zero? r) (ffi/read uid :uint32 0))))))

(defn- read-line-bytes
  "Read one newline-terminated frame before `deadline-ns`. Returns bytes
  without the newline, nil on EOF/timeout before a newline, or max+1 bytes for
  an oversized frame so the protocol parser can reject it immediately."
  [fd deadline-ns]
  (ffi/with-alloc [buf 4096]
    (loop [acc []]
      (cond
        (> (count acc) max-bytes)
        (byte-array (map unchecked-byte acc))

        :else
        (when-let [remaining (remaining-ms deadline-ns)]
          (let [revents (poll-events fd POLLIN (min 100 remaining))]
            (cond
              (nil? revents) nil
              (pos? (bit-and revents POLLIN))
              (let [wanted (min 4096 (- (inc max-bytes) (count acc)))
                    [n e] (c-read fd buf wanted)]
                (cond
                  (zero? n) nil
                  (neg? n) (if (retryable-io-error? e) (recur acc) nil)
                  :else
                  (let [chunk (mapv #(ffi/read buf :uint8 %) (range n))
                        nl (first (keep-indexed #(when (= 10 %2) %1) chunk))
                        acc' (into acc (if (nil? nl) chunk (take nl chunk)))]
                    (if (some? nl)
                      (byte-array (map unchecked-byte acc'))
                      (recur acc')))))
              (terminal-poll-event? revents) nil
              :else (recur acc))))))))

(defn- write-all!
  "Write all bytes before `deadline-ns` on a non-blocking fd."
  [fd bytes deadline-ns]
  (let [n (alength bytes)]
    (ffi/with-alloc [buf (max 1 n)]
      (ffi/write-array buf bytes)
      (loop [offset 0]
        (if (>= offset n)
          true
          (when-let [remaining (remaining-ms deadline-ns)]
            (let [revents (poll-events fd POLLOUT (min 100 remaining))]
              (cond
                (nil? revents) false
                (pos? (bit-and revents POLLOUT))
                (let [[written e] (c-write fd (+ buf offset) (- n offset))]
                  (cond
                    (pos? written) (recur (+ offset written))
                    (zero? written) false
                    (retryable-io-error? e) (recur offset)
                    :else false))
                (terminal-poll-event? revents) false
                :else (recur offset)))))))))

(defn serve-one!
  "If a client is waiting on `listen-fd`, accept it and handle one request:
  read the line, resolve the peer uid, call `(handler bytes peer-uid)` for a
  response byte array, write it, and close. At most one connection. Returns
  true if one was served. `accept-timeout-ms` bounds the wait for a client."
  [listen-fd handler accept-timeout-ms]
  (when (poll-ready? listen-fd POLLIN accept-timeout-ms)
    (let [[fd _] (c-accept listen-fd ffi/null ffi/null)]
      (if (neg? fd)
        false
        (do
          (let [request-deadline (+ (System/nanoTime) request-budget-ns)
                after-write
                (try
                  (set-nosigpipe fd)
                  (set-nonblocking! fd)
                  (when-let [req (read-line-bytes fd request-deadline)]
                    (let [result (handler req (peer-uid fd))
                          response-bytes (if (map? result) (:response-bytes result) result)
                          after-write (when (map? result) (:after-write result))
                          response-deadline (+ (System/nanoTime) response-budget-ns)]
                      (when (and response-bytes
                                 (write-all! fd response-bytes response-deadline))
                        after-write)))
                  (finally (c-close fd)))]
            (when after-write (after-write)))
          true)))))

(defn close! [fd path] (when fd (c-close fd)) (when path (c-unlink path)))

;; --- a minimal client, for tests and the agent's status polling -----------

(defn request!
  "Connect to the socket at `path`, send `line` (a string, newline appended if
  missing), read the response line, and close. Returns the response string, or
  nil on failure."
  [path line]
  (let [path-bs (path-bytes path)
        [fd _] (c-socket AF_UNIX SOCK_STREAM 0)]
    (when (>= fd 0)
      (try
        (set-nonblocking! fd)
        (ffi/with-alloc [addr sun-len]
          (let [request-deadline (+ (System/nanoTime) request-budget-ns)
                addr (write-sockaddr! addr path-bs)]
            (when (connect-before! fd addr request-deadline)
              (let [msg (if (.endsWith ^String line "\n") line (str line "\n"))
                    bs (.getBytes msg "UTF-8")]
                (when (write-all! fd bs request-deadline)
                  (let [response-deadline (+ (System/nanoTime) response-budget-ns)]
                    (when-let [resp (read-line-bytes fd response-deadline)]
                      (String. resp "UTF-8"))))))))
        (finally (c-close fd))))))
