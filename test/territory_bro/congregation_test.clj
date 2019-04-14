;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.congregation-test
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [hugsql.core :as hugsql]
            [mount.core :as mount]
            [territory-bro.config :as config]
            [territory-bro.congregation :refer :all]
            [territory-bro.db :as db])
  (:import (java.net URL)
           (org.flywaydb.core Flyway)))

(defn db-fixture [f]
  (mount/stop) ; during interactive development, app might be running when tests start
  (mount/start-with-args {:test true}
                         #'config/env
                         #'db/databases)
  (f)
  (mount/stop))

(use-fixtures :once db-fixture)

(defn ^"[Ljava.lang.String;" strings [& strings]
  (into-array String strings))

(defn ^Flyway master-db-migrations [schema]
  (-> (Flyway/configure)
      (.dataSource (get-in db/databases [:default :datasource]))
      (.schemas (strings schema))
      (.locations (strings "classpath:db/flyway/master"))
      (.load)))

(defn ^Flyway tenant-db-migrations [schema]
  (-> (Flyway/configure)
      (.dataSource (get-in db/databases [:default :datasource]))
      (.schemas (strings schema))
      (.locations (strings "classpath:db/flyway/tenant"))
      (.load)))

(def congregation-queries (atom {:resource (io/resource "db/hugsql/congregation.sql")}))

(defn load-queries []
  ;; TODO: implement detecting resource changes to clojure.tools.namespace.repl/refresh
  (let [{:keys [queries resource last-modified]} @congregation-queries
        current-last-modified (-> ^URL resource
                                  (.openConnection)
                                  (.getLastModified))]
    (if (= last-modified current-last-modified)
      queries
      (:queries (reset! congregation-queries
                        {:resource resource
                         :queries (hugsql/map-of-db-fns resource)
                         :last-modified current-last-modified})))))

(defn query [conn name & params]
  (let [query-fn (get-in (load-queries) [name :fn])]
    (assert query-fn (str "query not found: " name))
    (apply query-fn conn params)))

(defn create-congregation! [conn name schema-name]
  (let [id (:congregation_id (first (query conn :create-congregation
                                           {:name name
                                            :schema_name schema-name})))
        tenant (tenant-db-migrations schema-name)]
    (.migrate tenant)
    id))

(defn delete-congregations! [conn]
  (doseq [congregation (jdbc/query conn ["select schema_name from congregation"])]
    (jdbc/execute! conn [(str "drop schema " (:schema_name congregation) " cascade")]))
  (jdbc/execute! conn ["delete from congregation"]))

(deftest congregations-test
  (let [master (master-db-migrations "test_master")
        tenant (tenant-db-migrations "test_tenant")]
    (.clean tenant)
    (.clean master)
    (.migrate master)
    (.migrate tenant))
  (jdbc/with-db-transaction [conn (:default db/databases) {:isolation :serializable}]

    (jdbc/execute! conn ["set search_path to test_tenant,test_master"])

    (testing "No congregations"
      (is (= [] (query conn :get-congregations))))

    (testing "Create congregation"
      (let [id (create-congregation! conn "foo" "foo_schema")]
        (is id)
        (is (= [{:congregation_id id, :name "foo", :schema_name "foo_schema"}]
               (query conn :get-congregations)))
        (is (= [] (jdbc/query conn ["select * from foo_schema.territory"]))
            "should create congregation schema")))
    (delete-congregations! conn))

  (testing "lists congregations to which the user has access")
  (testing "hides congregations to which the user has no access")
  (testing "superadmin can access all congregations"))
