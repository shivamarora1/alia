(ns qbits.alia.result-set
  (:require
   [qbits.alia.gettable-by-index :as gettable-by-index]
   [qbits.alia.completable-future :as cf]
   [qbits.alia.error :as err])
  (:import
   [com.datastax.oss.driver.api.core
    CqlIdentifier]
   [com.datastax.oss.driver.api.core.cql
    ResultSet
    AsyncResultSet
    Row
    ColumnDefinitions
    ColumnDefinition]
   [java.util.concurrent Executor CompletionStage ExecutionException]))

;; defintes the interface of the object given to the :result-set-fn
;; for execute-sync queries, along with ISeqable and IReduceInit
(defprotocol PResultSet
  (execution-info [this]))

;; defines the interface of the object given to the :result-set-fn
;; for each page of execute-async queries, along with
;; PResultSet, ISeqable and IReduceInit
(defprotocol PSeqableAsyncResultSet)

;; defines the type returned by execute-async
(defprotocol PAsyncResultSetPage
  (has-more-pages? [this])
  (fetch-next-page [this]))

;; Shamelessly inspired from https://github.com/ghadishayban/squee's
;; monoid'ish appoach
(defprotocol RowGenerator
  (init-row [this] "Constructs a row base")
  (conj-row [this r k v] "Adds a entry/col to a row")
  (finalize-row [this r] "\"completes\" the row"))

(defn create-row-gen->map-like
  "create a row-generator for map-like things"
  [{constructor :constructor
    table-ns? :table-ns?}]
  (reify RowGenerator
    (init-row [_] (transient {}))
    (conj-row [_ row col-def v]
      (if table-ns?
        (let [^CqlIdentifier col-name (.getName ^ColumnDefinition col-def)
              ^CqlIdentifier col-table (.getTable ^ColumnDefinition col-def)]
          (assoc!
           row
           (keyword (.asInternal col-table) (.asInternal col-name))
           v))
        (let [^CqlIdentifier col-name (.getName ^ColumnDefinition col-def)]
          (assoc!
           row
           (keyword (.asInternal col-name))
           v))))
    (finalize-row [_ row]
      (if constructor
        (constructor (persistent! row))
        (persistent! row)))))

(def row-gen->map
  "Row Generator that returns map instances"
  (create-row-gen->map-like {}))

(def row-gen->ns-map
  "row-generator creating maps with table-namespaced keys"
  (create-row-gen->map-like {:table-ns? true}))

(defn row-gen->record
  "Row Generator that builds record instances"
  [record-ctor]
  (create-row-gen->map-like {:constructor record-ctor}))

(defn row-gen->ns-record
  "Row Generator that builds record instances with table-namspaced keys"
  [record-ctor]
  (create-row-gen->map-like {:constructor record-ctor
                             :table-ns? true}))

(def row-gen->vector
  "Row Generator that builds vector instances"
  (reify RowGenerator
    (init-row [_] (transient []))
    (conj-row [_ row _ v] (conj! row v))
    (finalize-row [_ row] (persistent! row))))

(defn decode-row
  [^Row row rg decode]
  (let [^ColumnDefinitions cdefs (.getColumnDefinitions row)
        len (.size cdefs)]
    (loop [idx (int 0)
           r (init-row rg)]
      (if (= idx len)
        (finalize-row rg r)
        (recur (unchecked-inc-int idx)
               (let [^ColumnDefinition cdef (.get cdefs idx)]
                 (conj-row rg r
                           cdef
                           (gettable-by-index/deserialize row idx decode))))))))

(defn ->result-set
  [^ResultSet rs row-generator codec]
  (let [row-generator (or row-generator row-gen->map)
        decode (:decoder codec)]
    (reify ResultSet

      PResultSet
      (execution-info [this]
        (.getExecutionInfos rs))

      clojure.lang.Seqable
      (seq [this]
        (map #(decode-row % row-generator decode)
             rs))

      clojure.lang.IReduceInit
      (reduce [this f init]
        (loop [ret init]
          (if-let [row (.one rs)]
            (let [ret (f ret (decode-row row row-generator decode))]
              (if (reduced? ret)
                @ret
                (recur ret)))
            ret))))))

(defn result-set
  [^ResultSet rs
   result-set-fn
   row-generator
   codec]
  ((or result-set-fn seq) (->result-set rs row-generator codec)))

(defn ->async-result-set
  "ISeqable and IReduceInit support for an AsyncResultSet, to
   be given to the :result-set-fn to create the :current-page object"
  [^AsyncResultSet async-result-set row-generator codec]
  (let [row-generator (or row-generator row-gen->map)
        decode (:decoder codec)]

    (reify

      PSeqableAsyncResultSet

      PResultSet
      (execution-info [this]
        (.getExecutionInfo async-result-set))

      clojure.lang.Seqable
      (seq [this]
        (map #(decode-row % row-generator decode)
             (.currentPage async-result-set)))

      clojure.lang.IReduceInit
      (reduce [this f init]
        (loop [ret init]
          (if-let [row (.one async-result-set)]
            (let [ret (f ret (decode-row row row-generator decode))]
              (if (reduced? ret)
                @ret
                (recur ret)))
            ret))))))

(declare handle-async-result-set-completion-stage)

;; the primary type returned by execute-async
(defrecord AliaAsyncResultSetPage [^AsyncResultSet async-result-set
                                   current-page
                                   opts]

  PResultSet
  (execution-info [this]
    (.getExecutionInfo async-result-set))

  PAsyncResultSetPage

  (has-more-pages? [this]
    (.hasMorePages async-result-set))

  (fetch-next-page [this]
    (if (true? (.hasMorePages async-result-set))
      (handle-async-result-set-completion-stage
       (.fetchNextPage async-result-set)
       opts)

      ;; return a CompletedFuture<nil> rather than plain nil
      ;; so the consumer get to expect a uniform type
      (cf/completed-future nil))))

(defn async-result-set-page
  "make a single page of an execute-async result

   the records from the current page are in the :current-page field
   which is constructed from the AsyncResultSet by the :result-set-fn

   subsequent pages can be fetched with PAsyncResultSetPage/fetch-next-page

   it's defined as a record rather than an opaque type to aid with debugging"
  [^AsyncResultSet ars
   {result-set-fn :result-set-fn
    row-generator :row-generator
    codec :codec
    :as opts}]
  (let [seqable-ars (->async-result-set ars row-generator codec)
        current-page ((or result-set-fn seq) seqable-ars)]

    (map->AliaAsyncResultSetPage
     {:async-result-set ars
      :current-page current-page
      :opts opts})))

(defn handle-async-result-set-completion-stage
  "handle a CompletionStage resulting from an .executeAsync of a query

   when successful, applies the row-generator and result-set-fn to the current page
   when failed, decorates the exception with query and value details"
  [^CompletionStage completion-stage
   {:keys [codec
           result-set-fn
           row-generator
           executor
           statement
           values]
    :as opts}]

  (cf/handle-completion-stage
   completion-stage

   (fn [async-result-set]
     (async-result-set-page async-result-set opts))

   (fn [err]
     (throw
      (err/ex->ex-info err {:query statement :values values})))

   opts))
