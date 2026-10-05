(ns gotosleep.sanity-test
  (:require [clojure.test :refer [deftest is]]))

(deftest runner-works
  (is (= 2 (+ 1 1))))
