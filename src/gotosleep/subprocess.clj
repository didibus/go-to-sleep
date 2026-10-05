(ns gotosleep.subprocess
  "Resource cleanup for jolt.process children.

  Dereferencing a Jolt process collects its output but does not close the
  native stdin, stdout, or stderr pipes. Long-lived callers must close them
  explicitly or eventually exhaust their file-descriptor limit.")

(defn- close-stream! [stream]
  (when stream
    (try
      (.close stream)
      (catch Throwable _ nil))))

(defn close-input!
  "Close a child's stdin when the caller will never write to it."
  [process]
  (close-stream! (:in process)))

(defn close-pipes!
  "Close every native pipe held by a jolt.process value. Safe to call more
  than once and on test doubles that are not process maps."
  [process]
  (close-input! process)
  (when-let [native (:proc process)]
    (close-stream! (.getInputStream native))
    (close-stream! (.getErrorStream native))))
