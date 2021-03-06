;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.api-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [ring.util.http-predicates :refer :all]
            [territory-bro.api :as api]
            [territory-bro.authentication :as auth]
            [territory-bro.config :as config]
            [territory-bro.congregation :as congregation]
            [territory-bro.db :as db]
            [territory-bro.events :as events]
            [territory-bro.fixtures :refer [db-fixture api-fixture]]
            [territory-bro.gis-user :as gis-user]
            [territory-bro.json :as json]
            [territory-bro.jwt :as jwt]
            [territory-bro.jwt-test :as jwt-test]
            [territory-bro.projections :as projections]
            [territory-bro.router :as router]
            [territory-bro.user :as user])
  (:import (java.time Instant)
           (java.util UUID)))

(use-fixtures :once (join-fixtures [db-fixture api-fixture]))

(defn- get-cookies [response]
  (->> (get-in response [:headers "Set-Cookie"])
       (map (fn [header]
              (let [[name value] (-> (first (str/split header #";" 2))
                                     (str/split #"=" 2))]
                [name {:value value}])))
       (into {})))

(deftest get-cookies-test
  (is (= {} (get-cookies {})))
  (is (= {"ring-session" {:value "123"}}
         (get-cookies {:headers {"Set-Cookie" ["ring-session=123"]}})))
  (is (= {"ring-session" {:value "123"}}
         (get-cookies {:headers {"Set-Cookie" ["ring-session=123;Path=/;HttpOnly;SameSite=Strict"]}})))
  (is (= {"foo" {:value "123"}
          "bar" {:value "456"}}
         (get-cookies {:headers {"Set-Cookie" ["foo=123" "bar=456"]}}))))

(defn parse-json [body]
  (cond
    (nil? body) body
    (string? body) body
    :else (json/parse-string (slurp (io/reader body)))))

(defn read-body [response]
  (update response :body parse-json))

(defn app [request]
  (-> request router/app read-body))

(defn assert-response [response predicate]
  (assert (predicate response)
          (str "Unexpected response " response))
  response)

(defn login! [app]
  (let [response (-> (request :post "/api/login")
                     (json-body {:idToken jwt-test/token})
                     app
                     (assert-response ok?))]
    {:cookies (get-cookies response)}))

(defn logout! [app session]
  (-> (request :post "/api/logout")
      (merge session)
      app
      (assert-response ok?)))


(deftest format-for-api-test
  (is (= {} (api/format-for-api {})))
  (is (= {"foo" 1} (api/format-for-api {:foo 1})))
  (is (= {"fooBar" 1} (api/format-for-api {:foo-bar 1})))
  (is (= {"bar" 1} (api/format-for-api {:foo/bar 1})))
  (is (= [{"foo" 1} {"bar" 2}] (api/format-for-api [{:foo 1} {:bar 2}]))))

(deftest basic-routes-test
  (testing "index"
    (let [response (-> (request :get "/")
                       app)]
      (is (ok? response))))

  (testing "page not found"
    (let [response (-> (request :get "/invalid")
                       app)]
      (is (not-found? response)))))

(deftest login-test
  (testing "login with valid token"
    (let [response (-> (request :post "/api/login")
                       (json-body {:idToken jwt-test/token})
                       app)]
      (is (ok? response))
      (is (= "Logged in" (:body response)))
      (is (= ["ring-session"] (keys (get-cookies response))))))

  (testing "user is saved on login"
    (let [user (user/get-by-subject db/database (:sub (jwt/validate jwt-test/token config/env)))]
      (is user)
      (is (= "Esko Luontola" (get-in user [:user/attributes :name])))))

  (testing "login with expired token"
    (binding [config/env (assoc config/env :now #(Instant/now))]
      (let [response (-> (request :post "/api/login")
                         (json-body {:idToken jwt-test/token})
                         app)]
        (is (forbidden? response))
        (is (= "Invalid token" (:body response)))
        (is (empty? (get-cookies response))))))

  (testing "dev login"
    (binding [config/env (assoc config/env :dev true)]
      (let [response (-> (request :post "/api/dev-login")
                         (json-body {:sub "developer"
                                     :name "Developer"
                                     :email "developer@example.com"})
                         app)]
        (is (ok? response))
        (is (= "Logged in" (:body response)))
        (is (= ["ring-session"] (keys (get-cookies response)))))))

  (testing "user is saved on dev login"
    (let [user (user/get-by-subject db/database "developer")]
      (is user)
      (is (= "Developer" (get-in user [:user/attributes :name])))))

  (testing "dev login outside dev mode"
    (let [response (-> (request :post "/api/dev-login")
                       (json-body {:sub "developer"
                                   :name "Developer"
                                   :email "developer@example.com"})
                       app)]
      (is (forbidden? response))
      (is (= "Dev mode disabled" (:body response)))
      (is (empty? (get-cookies response))))))

(deftest dev-login-test
  (testing "authenticates as anybody in dev mode"
    (binding [config/env {:dev true}
              api/save-user-from-jwt! (fn [_]
                                        (UUID. 0 1))]
      (is (= {:status 200,
              :headers {},
              :body "Logged in",
              :session {::auth/user {:user/id (UUID. 0 1)
                                     :sub "sub",
                                     :name "name",
                                     :email "email"}}}
             (api/dev-login {:params {:sub "sub"
                                      :name "name"
                                      :email "email"}})))))

  (testing "is disabled when not in dev mode"
    (binding [config/env {:dev false}]
      (is (= {:status 403
              :headers {}
              :body "Dev mode disabled"}
             (api/dev-login {:params {:sub "sub"
                                      :name "name"
                                      :email "email"}}))))))

(deftest authorization-test
  (testing "before login"
    (let [response (-> (request :get "/api/congregations")
                       app)]
      (is (unauthorized? response))))

  (let [session (login! app)]
    (testing "after login"
      (let [response (-> (request :get "/api/congregations")
                         (merge session)
                         app)]
        (is (ok? response))))

    (testing "after logout"
      (logout! app session)
      (let [response (-> (request :get "/api/congregations")
                         (merge session)
                         app)]
        (is (unauthorized? response))))))

(deftest create-congregation-test
  (let [session (login! app)
        response (-> (request :post "/api/congregations")
                     (json-body {:name "foo"})
                     (merge session)
                     app)]
    (is (ok? response))
    (is (:id (:body response)))

    (let [cong-id (UUID/fromString (:id (:body response)))]
      (testing "grants access to the current user"
        (is (= 1 (count (congregation/get-users (projections/current-state db/database) cong-id)))))

      (testing "creates a GIS user for the current user"
        (is (= 1 (count (gis-user/get-gis-users (projections/current-state db/database) cong-id)))))))

  (testing "requires login"
    (let [response (-> (request :post "/api/congregations")
                       (json-body {:name "foo"})
                       app)]
      (is (unauthorized? response)))))

(deftest list-congregations-test
  (let [session (login! app)
        response (-> (request :get "/api/congregations")
                     (merge session)
                     app)]
    (is (ok? response))
    (is (sequential? (:body response))))

  (testing "requires login"
    (let [response (-> (request :get "/api/congregations")
                       app)]
      (is (unauthorized? response)))))

(defn revoke-access-from-all! [cong-id]
  ;; TODO: create an API for changing permissions
  (db/with-db [conn {}]
    (doseq [user-id (congregation/get-users (projections/current-state conn) cong-id)]
      (binding [events/*current-system* "test"]
        (congregation/revoke! conn cong-id user-id :view-congregation)
        (congregation/revoke! conn cong-id user-id :configure-congregation))))
  (projections/refresh-async!)
  (projections/await-refreshed))


(deftest get-congregation-test
  (let [session (login! app)
        response (-> (request :post "/api/congregations")
                     (json-body {:name "foo"})
                     (merge session)
                     app
                     (assert-response ok?))
        cong-id (UUID/fromString (:id (:body response)))]

    (testing "get congregation"
      (let [response (-> (request :get (str "/api/congregation/" cong-id))
                         (merge session)
                         app)]
        (is (ok? response))
        (is (= (str cong-id) (:id (:body response))))
        ;; TODO: use response schemas to validate responses automatically and remove these checks
        (is (sequential? (:territories (:body response))))
        (is (sequential? (:congregationBoundaries (:body response))))
        (is (sequential? (:subregions (:body response))))
        (is (sequential? (:cardMinimapViewports (:body response))))
        (is (sequential? (:users (:body response))))
        (is (map? (:permissions (:body response))))))

    (testing "requires login"
      (let [response (-> (request :get (str "/api/congregation/" cong-id))
                         app)]
        (is (unauthorized? response))))

    (testing "wrong ID"
      (let [response (-> (request :get (str "/api/congregation/" (UUID/randomUUID)))
                         (merge session)
                         app)]
        (is (forbidden? response)))) ; same as when ID exists but user has no access

    (testing "no access"
      (revoke-access-from-all! cong-id)
      (let [response (-> (request :get (str "/api/congregation/" cong-id))
                         (merge session)
                         app)]
        (is (forbidden? response))))))

(deftest download-qgis-project-test
  (let [session (login! app)
        response (-> (request :post "/api/congregations")
                     (json-body {:name "Example Congregation"})
                     (merge session)
                     app
                     (assert-response ok?))
        cong-id (UUID/fromString (:id (:body response)))]

    (let [response (-> (request :get (str "/api/congregation/" cong-id "/qgis-project"))
                       (merge session)
                       app)]
      (is (ok? response))
      (is (str/includes? (:body response) "<qgis"))
      (is (str/includes? (get-in response [:headers "Content-Disposition"])
                         "Example Congregation.qgs")))

    (testing "requires login"
      (let [response (-> (request :get (str "/api/congregation/" cong-id "/qgis-project"))
                         app)]
        (is (unauthorized? response))))

    (testing "requires GIS access"
      ;; TODO: create API for getting the current user's ID
      ;; TODO: create API for removing GIS access from a user
      (db/with-db [conn {}]
        (doseq [gis-user (gis-user/get-gis-users (projections/current-state conn) cong-id)]
          (binding [events/*current-system* "test"]
            (gis-user/delete-gis-user! conn (projections/current-state conn) cong-id (:user/id gis-user)))))
      (projections/refresh-async!)
      (projections/await-refreshed)

      (let [response (-> (request :get (str "/api/congregation/" cong-id "/qgis-project"))
                         (merge session)
                         app)]
        (is (forbidden? response))
        (is (str/includes? (:body response) "No GIS access"))))))

(deftest add-user-test
  (let [session (login! app)
        response (-> (request :post "/api/congregations")
                     (json-body {:name "Congregation"})
                     (merge session)
                     app
                     (assert-response ok?))
        cong-id (UUID/fromString (:id (:body response)))
        new-user-id (user/save-user! db/database "user1" {:name "User 1"})]

    (testing "add user"
      (let [response (-> (request :post (str "/api/congregation/" cong-id "/add-user"))
                         (json-body {:userId (str new-user-id)})
                         (merge session)
                         app)]
        (is (ok? response)))
      ;; TODO: check the result through the API
      (let [users (-> (projections/current-state db/database)
                      (congregation/get-users cong-id))]
        (is (contains? (set users) new-user-id))))

    (testing "invalid user"
      (let [response (-> (request :post (str "/api/congregation/" cong-id "/add-user"))
                         (json-body {:userId (str (UUID. 0 1))})
                         (merge session)
                         app)]
        (is (bad-request? response))
        (is (= {:errors [["no-such-user" "00000000-0000-0000-0000-000000000001"]]}
               (:body response)))))

    (testing "no access"
      (revoke-access-from-all! cong-id)
      (let [response (-> (request :post (str "/api/congregation/" cong-id "/add-user"))
                         (json-body {:userId (str new-user-id)})
                         (merge session)
                         app)]
        (is (forbidden? response))))))

(deftest rename-congregation-test
  (let [session (login! app)
        response (-> (request :post "/api/congregations")
                     (json-body {:name "Old Name"})
                     (merge session)
                     app
                     (assert-response ok?))
        cong-id (UUID/fromString (:id (:body response)))]

    (testing "rename congregation"
      (let [response (-> (request :post (str "/api/congregation/" cong-id "/rename"))
                         (json-body {:name "New Name"})
                         (merge session)
                         app)]
        (is (ok? response)))
      (let [response (-> (request :get (str "/api/congregation/" cong-id))
                         (merge session)
                         app)]
        (is (ok? response))
        (is (= "New Name" (:name (:body response))))))

    (testing "no access"
      (revoke-access-from-all! cong-id)
      (let [response (-> (request :post (str "/api/congregation/" cong-id "/rename"))
                         (json-body {:name "should not be allowed"})
                         (merge session)
                         app)]
        (is (forbidden? response))))))
