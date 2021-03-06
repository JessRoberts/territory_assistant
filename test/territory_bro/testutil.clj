;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.testutil
  (:require [clojure.test :refer :all]
            [territory-bro.events :as events])
  (:import (java.time Instant)
           (java.util.regex Pattern)))

(defn re-equals [^String s]
  (re-pattern (str "^" (Pattern/quote s) "$")))

(defn re-contains [^String s]
  (re-pattern (Pattern/quote s)))

(defmacro grab-exception [& body]
  `(try
     (let [result# (do ~@body)]
       (do-report {:type :fail
                   :message "should have thrown an exception, but did not"
                   :expected (seq (into [(symbol "grab-exception")]
                                        '~body))
                   :actual result#})
       result#)
     (catch Throwable t#
       t#)))

(defn validate-test-events [events]
  (->> events
       (map-indexed (fn [index event]
                      (merge {:event/system "test"
                              :event/time (Instant/ofEpochSecond (inc index))}
                             event)))
       (events/validate-events)))

(defn apply-events [projection events]
  (reduce projection nil (validate-test-events events)))
