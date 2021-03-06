;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.commands-test
  (:require [clojure.test :refer :all]
            [schema.core :as s]
            [territory-bro.commands :as commands]
            [territory-bro.testutil :refer [re-equals re-contains]])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)
           (java.util UUID)))

(def valid-command {:command/type :congregation.command/rename-congregation
                    :command/time (Instant/now)
                    :command/user (UUID/randomUUID)
                    :congregation/id (UUID/randomUUID)
                    :congregation/name ""})
(def invalid-command (dissoc valid-command :congregation/name))
(def unknown-command (assoc valid-command :command/type :foo))

;; TODO: deduplicate event & command validation infra

(deftest command-schema-test
  (testing "check specific command schema"
    (is (nil? (s/check commands/RenameCongregation valid-command))))

  (testing "check generic command schema"
    (is (nil? (s/check commands/Command valid-command))))

  (testing "invalid command"
    (is (= {:congregation/name 'missing-required-key}
           (s/check commands/Command invalid-command))))

  (testing "unknown command type"
    ;; TODO: produce a helpful error message
    (is (s/check commands/Command unknown-command))))

(deftest validate-command-test
  (testing "valid command"
    (is (= valid-command (commands/validate-command valid-command))))

  (testing "invalid command"
    (is (thrown-with-msg? ExceptionInfo (re-contains "{:congregation/name missing-required-key}")
                          (commands/validate-command invalid-command))))

  (testing "unknown command type"
    (is (thrown-with-msg? ExceptionInfo (re-equals "Unknown command type :foo")
                          (commands/validate-command unknown-command)))))
