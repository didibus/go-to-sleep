(ns gotosleep.lock.main-test
  "Covers the helper's logged uid line. The lock call itself is never exercised
  in tests."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [gotosleep.lock.main :as lock]))

(deftest uid-line-format
  (let [line (lock/uid-line)]
    (is (str/starts-with? line "uid="))
    (is (re-matches #"uid=\d+" line))))
