;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.gis-user-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [territory-bro.config :as config]
            [territory-bro.congregation :as congregation]
            [territory-bro.db :as db]
            [territory-bro.fixtures :refer [db-fixture]]
            [territory-bro.gis-user :as gis-user]
            [territory-bro.user :as user])
  (:import (org.postgresql.util PSQLException)))

(use-fixtures :once db-fixture)

(deftest gis-users-test
  (db/with-db [conn {}]
    (jdbc/db-set-rollback-only! conn)

    (let [cong-id (congregation/create-congregation! conn "cong1")
          cong-id2 (congregation/create-congregation! conn "cong2")
          user-id (user/save-user! conn "user1" {})
          user-id2 (user/save-user! conn "user2" {})]

      (gis-user/create-gis-user! conn cong-id user-id)
      (gis-user/create-gis-user! conn cong-id user-id2)
      (gis-user/create-gis-user! conn cong-id2 user-id)
      (gis-user/create-gis-user! conn cong-id2 user-id2)
      (testing "create & get GIS user"
        (let [user (gis-user/get-gis-user conn cong-id user-id)]
          (is (::gis-user/username user))
          (is (= 50 (count ((::gis-user/password user)))))))

      (testing "get GIS users by congregation"
        (is (= #{[cong-id user-id]
                 [cong-id user-id2]}
               (->> (gis-user/get-gis-users conn {:congregation cong-id})
                    (map (juxt ::gis-user/congregation ::gis-user/user))
                    (into #{})))))

      (testing "get GIS users by user"
        (is (= #{[cong-id user-id]
                 [cong-id2 user-id]}
               (->> (gis-user/get-gis-users conn {:user user-id})
                    (map (juxt ::gis-user/congregation ::gis-user/user))
                    (into #{})))))

      (testing "delete GIS user"
        (gis-user/delete-gis-user! conn cong-id user-id)
        (is (nil? (gis-user/get-gis-user conn cong-id user-id)))
        (is (gis-user/get-gis-user conn cong-id2 user-id2)
            "should not delete unrelated users")))))

(defn- create-test-data! []
  (db/with-db [conn {}]
    (let [cong-id (congregation/create-congregation! conn "cong")
          cong (congregation/get-unrestricted-congregation conn cong-id)
          user-id (user/save-user! conn "user" {})
          _ (gis-user/create-gis-user! conn cong-id user-id)
          gis-user (gis-user/get-gis-user conn cong-id user-id)]
      {:cong-id cong-id
       :user-id user-id
       :schema (::congregation/schema-name cong)
       :username (::gis-user/username gis-user)
       :password ((::gis-user/password gis-user))})))

(deftest gis-user-database-access-test
  (let [{:keys [cong-id user-id schema username password]} (create-test-data!)
        db-spec {:connection-uri (-> (:database-url config/env)
                                     (str/replace #"\?.*" "")) ; strip username and password
                 :user username
                 :password password}]

    (testing "can login to the database"
      (is (= [{:test 1}] (jdbc/query db-spec ["select 1 as test"]))))

    (testing "can view the tenant schema and the tables in it"
      (jdbc/with-db-transaction [conn db-spec]
        (is (jdbc/query conn [(str "select * from " schema ".territory")]))
        (is (jdbc/query conn [(str "select * from " schema ".congregation_boundary")]))
        (is (jdbc/query conn [(str "select * from " schema ".subregion")]))
        (is (jdbc/query conn [(str "select * from " schema ".card_minimap_viewport")]))))

    (testing "cannot view the master schema"
      (is (thrown-with-msg? PSQLException #"ERROR: permission denied for schema"
                            (jdbc/query db-spec [(str "select * from " (:database-schema config/env) ".congregation")]))))

    (testing "cannot login to database after user is deleted"
      (db/with-db [conn {}]
        (gis-user/delete-gis-user! conn cong-id user-id))
      (is (thrown-with-msg? PSQLException #"FATAL: password authentication failed for user"
                            (jdbc/query db-spec ["select 1 as test"]))))))

(deftest generate-password-test
  (let [a (gis-user/generate-password 10)
        b (gis-user/generate-password 10)]
    (is (= 10 (count a) (count b)))
    (is (not (= a b)))))

(deftest secret-test
  (let [secret (gis-user/secret "foo")]

    (testing "hides the secret when printed normally"
      (is (not (str/includes? (str secret) "foo")))
      (is (not (str/includes? (pr-str secret) "foo"))))

    (testing "exposes the secret when invoked as a function"
      (is (= "foo" (secret))))))
