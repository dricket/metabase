(ns metabase.sync.util
  "Utility functions and macros to abstract away some common patterns and operations across the sync processes, such as logging start/end messages."
  (:require [clojure.math.numeric-tower :as math]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase
             [driver :as driver]
             [events :as events]
             [util :as u]]
            [metabase.models.table :refer [Table]]
            [metabase.query-processor.interface :as i]
            [toucan.db :as db])
  (:import metabase.models.database.DatabaseInstance
           metabase.models.field.FieldInstance
           metabase.models.table.TableInstance))

(defn do-with-start-and-finish-logging
  "Implementation of `with-start-and-finish-logging`. Don't use this directly, prefer that instead."
  [message f]
  (let [start-time (System/nanoTime)]
    (log/info (u/format-color 'magenta "STARTING: %s" message))
    (f)
    (log/info (u/format-color 'magenta "FINSISHED: %s (%s)" message (u/format-nanoseconds (- (System/nanoTime) start-time))))))

(defmacro with-start-and-finish-logging
  "Log MESSAGE about a process starting, then run F, and then log a MESSAGE about it finishing.
   (The final message includes a summary of how long it took to run F.)"
  {:style/indent 1}
  [message & body]
  `(do-with-start-and-finish-logging ~message (fn [] ~@body)))


(defn do-with-sync-events
  "Impl for `with-sync-events`. Don't use this directly; use that instead."
  ;; we can do everyone a favor and infer the name of the individual begin and sync events
  ([event-name-prefix database-or-id f]
   (do-with-sync-events
    (keyword (str (name event-name-prefix) "-begin"))
    (keyword (str (name event-name-prefix) "-end"))
    database-or-id
    f))
  ([begin-event-name end-event-name database-or-id f]
   (let [start-time    (System/nanoTime)
         tracking-hash (str (java.util.UUID/randomUUID))]
     (events/publish-event! begin-event-name {:database_id (u/get-id database-or-id), :custom_id tracking-hash})
     (f)
     (let [total-time-ms (int (/ (- (System/nanoTime) start-time)
                                 1000000.0))]
       (events/publish-event! end-event-name {:database_id  (u/get-id database-or-id)
                                              :custom_id    tracking-hash
                                              :running_time total-time-ms})))))

(defmacro with-sync-events
  "Publish events related to beginning and ending a sync-like process, e.g. `:sync-database` or `:cache-values`, for a DATABASE-ID.
   BODY is executed between the logging of the two events."
  {:style/indent 2}
  [event-name-prefix database-or-id & body]
  `(do-with-sync-events ~event-name-prefix ~database-or-id (fn [] ~@body)))


(defmacro with-db-logging-disabled
  "Disable all QP and DB logging when running BODY. (This should be done for *all* sync-like processes to avoid cluttering the logs.)"
  {:style/indent 0}
  [& body]
  `(binding [i/*disable-qp-logging*  true
             db/*disable-db-logging* true]
     ~@body))


;; This looks something like {:sync #{1 2}, :cache #{2 3}} when populated.
;; Key is a type of sync operation, e.g. `:sync` or `:cache`; vals are sets of DB IDs undergoing that operation.
(defonce ^:private operation->db-ids (atom {}))

(defn do-with-duplicate-ops-prevented
  "Implementation for `with-duplicate-ops-prevented`; prefer that instead."
  [operation database-or-id f]
  (when-not (contains? (@operation->db-ids operation) (u/get-id database-or-id))
    (try
      ;; mark this database as currently syncing so we can prevent duplicate sync attempts (#2337)
      (swap! operation->db-ids update operation #(conj (or % #{}) (u/get-id database-or-id)))
      (println "Sync operations in flight:" @operation->db-ids) ; NOCOMMIT
      ;; do our work
      (f)
      ;; always cleanup our tracking when we are through
      (finally
        (swap! operation->db-ids update operation #(disj % (u/get-id database-or-id)))))))

(defmacro with-duplicate-ops-prevented
  "Run BODY in a way that will prevent it from simultaneously being ran more for a single database more than once for a given OPERATION.
   This prevents duplicate sync-like operations from taking place for a given DB, e.g. if a user hits the `Sync` button in the admin panel multiple times.
     ;; Only one `sync-db!` for `database-id` will be allowed at any given moment; duplicates will be ignored
     (with-duplicate-ops-prevented :sync database-id
       (sync-db! database-id))"
  {:style/indent 2}
  [operation database-or-id & body]
  `(do-with-duplicate-ops-prevented ~operation ~database-or-id (fn [] ~@body)))

(defn do-with-error-handling
  ([f]
   (do-with-error-handling "Error running sync step" f))
  ([message f]
   (try (f)
        (catch Throwable e
          (log/error (u/format-color 'red "%s: %s\n%s"
                       message
                       (or (.getMessage e) (class e))
                       (u/pprint-to-str (u/filtered-stacktrace e))))))))

(defmacro with-error-handling {:style/indent 1} [message & body]
  `(do-with-error-handling ~message (fn [] ~@body)))

(defmacro sync-in-context {:style/indent 1} [database & body]
  `(driver/sync-in-context (driver/->driver ~database) ~database
     (partial do-with-error-handling (fn [] ~@body))))

;; TODO - simplify this macro and the other ones that it uses!
(defmacro sync-operation {:style/indent 3} [operation database message & body]
  `(with-duplicate-ops-prevented ~operation ~database
     (with-sync-events ~operation ~database
       (with-start-and-finish-logging ~message
         (with-db-logging-disabled
           (sync-in-context ~database
             ~@body))))))


(def ^:private ^:const ^Integer emoji-meter-width 50)

(def ^:private progress-emoji
  ["😱"   ; face screaming in fear
   "😢"   ; crying face
   "😞"   ; disappointed face
   "😒"   ; unamused face
   "😕"   ; confused face
   "😐"   ; neutral face
   "😬"   ; grimacing face
   "😌"   ; relieved face
   "😏"   ; smirking face
   "😋"   ; face savouring delicious food
   "😊"   ; smiling face with smiling eyes
   "😍"   ; smiling face with heart shaped eyes
   "😎"]) ; smiling face with sunglasses

(defn- percent-done->emoji [percent-done]
  (progress-emoji (int (math/round (* percent-done (dec (count progress-emoji)))))))

(defn emoji-progress-bar
  "Create a string that shows progress for something, e.g. a database sync process.

     (emoji-progress-bar 10 40)
       -> \"[************······································] 😒   25%"
  [completed total]
  (let [percent-done (float (/ completed total))
        filleds      (int (* percent-done emoji-meter-width))
        blanks       (- emoji-meter-width filleds)]
    (str "["
         (str/join (repeat filleds "*"))
         (str/join (repeat blanks "·"))
         (format "] %s  %3.0f%%" (u/emoji (percent-done->emoji percent-done)) (* percent-done 100.0)))))

(defmacro with-emoji-progress-bar
  "Run BODY with access to a function that makes using our amazing emoji-progress-bar easy like Sunday morning.
   Calling the function will return the approprate string output for logging and automatically increment an internal counter as needed.
     (with-emoji-progress-bar [progress-bar 10]
       (dotimes [i 10]
         (println (progress-bar))))"
  {:style/indent 1}
  [[emoji-progress-fn-binding total-count] & body]
  `(let [finished-count#            (atom 0)
         total-count#               ~total-count
         ~emoji-progress-fn-binding (fn [] (emoji-progress-bar (swap! finished-count# inc) total-count#))]
     ~@body))


(defn db->sync-tables
  "Return all the Tables that should go through the sync processes for DATABASE-OR-ID."
  [database-or-id]
  (db/select Table, :db_id (u/get-id database-or-id), :active true, :visibility_type nil))


(defprotocol ^:private INameForLogging
  (name-for-logging [this]
    "Return an appropriate string for logging an object in sync logging messages.
     Should be something like \"postgres Database 'test-data'\""))

(extend-protocol INameForLogging
  DatabaseInstance
  (name-for-logging [{database-name :name, engine :engine}]
    (format "%s Database '%s'" (name engine) database-name))

  TableInstance
  (name-for-logging [{schema :schema, table-name :name}]
    (format "Table '%s'" (str (when (seq schema) (str schema ".")) table-name)))

  FieldInstance
  (name-for-logging [{field-name :name}]
    (format "Field '%s'" field-name)))
