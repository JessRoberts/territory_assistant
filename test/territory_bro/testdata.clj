;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.testdata
  (:require [clojure.test :refer :all]))

(def wkt-polygon "POLYGON((30 20,45 40,10 40,30 20))")
(def wkt-multi-polygon "MULTIPOLYGON(((30 20,45 40,10 40,30 20)),((15 5,40 10,10 20,5 10,15 5)))")
