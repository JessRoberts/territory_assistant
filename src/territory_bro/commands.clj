;; Copyright © 2015-2019 Esko Luontola
;; This software is released under the Apache License 2.0.
;; The license text is at http://www.apache.org/licenses/LICENSE-2.0

(ns territory-bro.commands
  (:require [schema-refined.core :as refined]
            [schema.core :as s]
            [schema.utils])
  (:import (java.time Instant)
           (java.util UUID)))

;;;; Schemas

(s/defschema UserCommand
  {:command/type s/Keyword
   :command/time Instant
   :command/user UUID})

(s/defschema SystemCommand
  {:command/type s/Keyword
   :command/time Instant
   :command/system s/Str})


;;; Congregation

(s/defschema AddUser
  (assoc UserCommand
         :command/type (s/eq :congregation.command/add-user)
         :congregation/id UUID
         :user/id UUID))

(s/defschema RenameCongregation
  (assoc UserCommand
         :command/type (s/eq :congregation.command/rename-congregation)
         :congregation/id UUID
         :congregation/name s/Str))


;;; DB Admin

(s/defschema EnsureGisUserAbsent
  (assoc SystemCommand
         :command/type (s/eq :db-admin.command/ensure-gis-user-absent)
         :user/id UUID
         :gis-user/username s/Str
         :congregation/id UUID
         :congregation/schema-name s/Str))

(s/defschema EnsureGisUserPresent
  (assoc SystemCommand
         :command/type (s/eq :db-admin.command/ensure-gis-user-present)
         :user/id UUID
         :gis-user/username s/Str
         :gis-user/password s/Str
         :congregation/id UUID
         :congregation/schema-name s/Str))

(s/defschema MigrateTenantSchema
  (assoc SystemCommand
         :command/type (s/eq :db-admin.command/migrate-tenant-schema)
         :congregation/id UUID
         :congregation/schema-name s/Str))


(def command-schemas
  {:congregation.command/add-user AddUser
   :congregation.command/rename-congregation RenameCongregation
   :db-admin.command/ensure-gis-user-absent EnsureGisUserAbsent
   :db-admin.command/ensure-gis-user-present EnsureGisUserPresent
   :db-admin.command/migrate-tenant-schema MigrateTenantSchema})

(s/defschema Command
  (apply refined/dispatch-on :command/type (flatten (seq command-schemas))))


;;;; Validation

(defn validate-command [command]
  (when-not (contains? command-schemas (:command/type command))
    (throw (ex-info (str "Unknown command type " (pr-str (:command/type command)))
                    {:command command})))
  (assert (contains? command-schemas (:command/type command))
          {:error [:unknown-command-type (:command/type command)]
           :command command})
  (s/validate Command command))
