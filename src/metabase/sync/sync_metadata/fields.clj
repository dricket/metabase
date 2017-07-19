(ns metabase.sync.sync-metadata.fields
  "Logic for updating Metabase Field models from metadata fetched from a physical DB."
  (:require [clojure
             [data :as data]
             [set :as set]]
            [clojure.tools.logging :as log]
            [metabase.models
             [field :refer [Field] :as field]
             [humanization :as humanization]]
            [metabase.sync
             [fetch-metadata :as fetch-metadata :refer [TableMetadataField]]
             [util :as sync-util]]
            [metabase.util :as u]
            [schema.core :as s]
            [toucan.db :as db]
            [metabase.util.schema :as su]
            [clojure.string :as str])
  (:import metabase.models.database.DatabaseInstance
           metabase.models.table.TableInstance))

#_(defn- update-field-from-field-def!
  "Update an EXISTING-FIELD from the given FIELD-DEF."
  {:arglists '([existing-field field-def])}
  [{:keys [id], :as existing-field} {field-name :name, :keys [base-type special-type pk? parent-id]}]
  (u/prog1 (assoc existing-field
             :base_type    base-type
             :display_name (or (:display_name existing-field)
                               (humanization/name->human-readable-name field-name))
             :special_type (or (:special_type existing-field)
                               special-type
                               (when pk?
                                 :type/PK))
             :parent_id    parent-id)
    ;; if we have a different base-type or special-type, then update
    ))

;; TODO - need to handle nested fields (!)
#_(defn- save-nested-fields!
  "Save any nested `Fields` for a given parent `Field`.
   All field-defs provided are assumed to be children of the given FIELD."
  [{parent-id :id, table-id :table_id, :as parent-field} nested-field-defs]
  ;; NOTE: remember that we never retire any fields in dynamic-schema tables
  (let [existing-field-name->field (u/key-by :name (db/select Field, :parent_id parent-id))]
    (u/prog1 (set/difference (set (map :name nested-field-defs)) (set (keys existing-field-name->field)))
      (when (seq <>)
        (log/debug (u/format-color 'blue "Found new nested fields for field '%s': %s" (:name parent-field) <>))))

    (doseq [nested-field-def nested-field-defs]
      (let [{:keys [nested-fields], :as nested-field-def} (assoc nested-field-def :parent-id parent-id)]
        ;; NOTE: this recursively creates fields until we hit the end of the nesting
        (if-let [existing-field (existing-field-name->field (:name nested-field-def))]
          ;; field already exists, so we UPDATE it
          (cond-> (update-field-from-field-def! existing-field nested-field-def)
                  nested-fields (save-nested-fields! nested-fields))
          ;; looks like a new field, so we CREATE it
          (cond-> (create-field-from-field-def! table-id nested-field-def)
            nested-fields (save-nested-fields! nested-fields)))))))


(s/defn ^:private ^:always-validate create-or-reactivate-fields!
  [table :- TableInstance, new-fields :- #{TableMetadataField}]
  (log/info "Found new fields:"
            (for [field new-fields]
              (sync-util/name-for-logging (field/map->FieldInstance field))))
  (doseq [{field-name :name, :keys [base-type special-type pk? parent-id raw-column-id]} new-fields]
    (if-let [existing-id (db/select-one-id Field :table_id (u/get-id table), :%lower.name (str/lower-case field-name) :active false)]
      ;; if the field already exists but was just marked inactive then reäctivate it
      (db/update! Field existing-id
        :active true)
      ;; otherwise insert a new field
      (db/insert! Field
        :table_id     (u/get-id table)
        :name         field-name
        :display_name (humanization/name->human-readable-name field-name)
        :base_type    base-type
        :special_type (or special-type
                          (when pk? :type/PK))
        :parent_id    parent-id))))


(s/defn ^:private ^:always-validate retire-fields!
  [table :- TableInstance, old-fields :- #{TableMetadataField}]
  (log/info "Marking fields as inactive:"
            (for [field old-fields]
              (sync-util/name-for-logging (field/map->FieldInstance field))))
  (doseq [field old-fields]
    (db/update-where! Field {:table_id    (u/get-id table)
                             :%lower.name (str/lower-case (:name field))
                             :active      true}
      :active false)))


(s/defn ^:private ^:always-validate update-metadata!
  "Make sure things like PK status and base-type are in sync with what has come back from the DB."
  [table :- TableInstance, fields :- #{TableMetadataField}]
  (let [existing-fields      (db/select [Field :base_type :special_type :name :id]
                               :table_id (u/get-id table)
                               :active   true)
        field-name->metadata (u/key-by (comp str/lower-case :name) fields)]
    ;; TODO - not sure this is really needed. Isn't there some better way to make sure Fields have up-to-date special types?
    (doseq [field existing-fields]
      (when-let [metadata (get field-name->metadata (str/lower-case (:name field)))]
        (db/update! Field (u/get-id field)
          (merge {:base_type (:base-type metadata)}
                 (when-not (:special-type field)
                   {:special_type (or (:special-type metadata)
                                      (when (:pk? metadata) :type/PK))})))))))


(s/defn ^:private ^:always-validate db-metadata :- #{TableMetadataField}
  [database :- DatabaseInstance, table :- TableInstance]
  (:fields (fetch-metadata/table-metadata database table)))

(s/defn ^:private ^:always-validate our-metadata :- #{TableMetadataField}
  [table :- TableInstance]
  (set (for [field (db/select [Field :name :base_type :special_type]
                     :table_id (u/get-id table)
                     :parent_id nil
                     :active   true)]
         {:name         (:name field)
          :base-type    (:base_type field)
          :special-type (:special_type field)
          :pk?          (isa? (:special_type field) :type/PK)})))


(s/defn ^:private ^:always-validate diff-fields :- #{TableMetadataField}
  "Return the set of Fields (based on case-insensitive name) that are only in A and not in B."
  [a :- #{TableMetadataField}, b :- #{TableMetadataField}]
  (let [field-names-in-b (set (map (comp str/lower-case :name)
                                   b))]
    (set (for [field a
               :when (not (contains? field-names-in-b (str/lower-case (:name field))))]
           field))))


(s/defn ^:private ^:always-validate sync-fields-for-table!
  [database :- DatabaseInstance, table :- TableInstance]
  (let [db-metadata  (db-metadata database table)
        our-metadata (our-metadata table)
        new-fields   (diff-fields db-metadata our-metadata)
        old-fields   (diff-fields our-metadata db-metadata)]
    ;; create new tables as needed or mark them as active again
    (when (seq new-fields)
      (create-or-reactivate-fields! table new-fields))
    ;; mark old fields as inactive
    (when (seq old-fields)
      (retire-fields! table old-fields))
    ;; now that tables are synced and fields created as needed make sure field properties are in sync
    (update-metadata! table db-metadata)))


(s/defn ^:always-validate sync-fields!
  [database :- DatabaseInstance]
  (doseq [table (sync-util/db->sync-tables database)]
    (sync-fields-for-table! database table)))