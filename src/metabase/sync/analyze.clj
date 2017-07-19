(ns metabase.sync.analyze
  "Logic responsible for doing deep 'analysis' of the data inside a database.
   This is significantly more expensive than the basic sync-metadata step, and involves things
   like running MBQL queries and fetching values to do things like determine Table row counts
   and infer field special types."
  (:require [metabase.sync
             [interface :as i]
             [util :as sync-util]]
            [metabase.sync.analyze
             [special-types :as special-types]
             [table-row-count :as table-row-count]]
            [schema.core :as s]))

(s/defn ^:always-validate analyze-db!
  "Perform in-depth analysis on the data for all Tables in a given DATABASE.
   This is dependent on what each database driver supports, but includes things like cardinality testing and table row counting."
  [database :- i/DatabaseInstance]
  (sync-util/sync-operation :database-analyze database (format "Analyze data for %s" (sync-util/name-for-logging database))
    (table-row-count/update-table-row-counts! database)
    (special-types/infer-special-types! database)))
