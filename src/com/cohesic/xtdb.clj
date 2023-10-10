(ns com.cohesic.xtdb
  (:require
   [xtdb.api :as xt])
  (:import
   java.util.Date))

(defn tx-op?
  "Return true if the input operation and the transaction operation
  match."
  [op tx-op]
  (= op (first tx-op)))

(def ^{:doc "Return true if the input transaction operation is a match."} match-tx-op?
  #(tx-op? ::xt/match %))
(def ^{:doc "Return true if the input transaction operation is a put."} put-tx-op?
  #(tx-op? ::xt/put %))
(def ^{:doc "Return true if the input transaction operation is a delete."} delete-tx-op?
  #(tx-op? ::xt/delete %))
(def ^{:doc "Return true if the input transaction operation is an evict."} evict-tx-op?
  #(tx-op? ::xt/evict %))

(defn history-item-after?
  "True if the input history item comes after the input time.

This version of the function is memoized."
  [history-item ^Date time]
  (.after (::xt/valid-time history-item) time))

(defn history-item-before?
  "True if the input history item comes before the input time.

This version of the function is memoized."
  [history-item ^Date time]
  (.before (::xt/valid-time history-item) time))

(defn history-item->match
  "Produce an XTDB ::xt/match from this history item.

  The entity id cannot be nil and will be taken either from the history
  item document or from the input options.

  Opts are:
    {:entity-id        some?
     :start-valid-time java.util.Date}"
  [history-item & {:keys [entity-id start-valid-time]}]
  (let [doc (::xt/doc history-item)
        entity-id (doto (or entity-id (:xt/id doc))
                   (assert "Both history item and entity id are nil."))
        start-valid-time (or start-valid-time (::xt/valid-time history-item))]
    ;; NOTE: we want to match against a nil document, e.g.: when the entity was deleted
    (cond-> [::xt/match entity-id doc] start-valid-time (conj start-valid-time))))

(defn history-item->put
  "Produce an XTDB ::xt/put from this history item.

  Opts are:
    {:start-valid-time java.util.Date
     :end-valid-time   java.util.Date}"
  [history-item & {:keys [start-valid-time end-valid-time]}]
  (let [doc (doto (::xt/doc history-item)
             (assert (str "The input history item does not contain a document. "
                          "Make sure you use xt/entity-history with :with-docs? true")))
        start-valid-time (or start-valid-time (::xt/valid-time history-item))]
    (->> [::xt/put doc start-valid-time end-valid-time]
         (filterv some?))))

(defn tx-op-entity
  "Helper to fetch the entity of a tx-op."
  [tx-op]
  (case (first tx-op)
    ::xt/put (get tx-op 1)
    ::xt/delete nil
    ::xt/match (get tx-op 2)))

(defn tx-op-entity-id
  "Helper to fetch the entity id of a tx-op."
  [tx-op]
  (case (first tx-op)
    ::xt/put nil
    ::xt/delete (get tx-op 1)
    ::xt/match (get tx-op 1)))

(defn tx-op-start-valid-time
  "Helper to fetch the start valid time for a tx-op."
  [tx-op]
  (case (first tx-op)
    ::xt/put (get tx-op 2)
    ::xt/delete (get tx-op 2)
    ::xt/match (get tx-op 3)))

(defn tx-op-end-valid-time
  "Helper to fetch the end valid time of a tx-op."
  [tx-op]
  (case (first tx-op)
    ::xt/put (get tx-op 3)
    ::xt/delete (get tx-op 3)
    ::xt/match nil))

(defn reverse-history-before-time
  "Compute transaction operations for reverting the entity as of the input
  time.

  The behaviour is the following
    * In case of no history a ::xt/delete is returned.
    * In case of no history _item_ right before the input time, a ::xt/delete is returned
    * In case of history item right before the input time, a ::xt/put + ::xt/match is returned

  The function assumes history in descending order (most recent items first).

  The following options are passed to the generated transaction ops (i.e.: ::xt/put):
    {:start-valid-time java.util.Date
     :end-valid-time   java.util.Date}"
  ([history entity-id ^Date as-of-time]
   (reverse-history-before-time history entity-id as-of-time nil))
  ([history entity-id ^Date as-of-time {:keys [start-valid-time end-valid-time] :as opts}]
   (if-let [version-at-time (->> history
                                 (filter #(history-item-before? % as-of-time))
                                 first)]
     (let [latest-version (first history)]
       [(history-item->match latest-version (assoc opts :entity-id entity-id))
        (history-item->put version-at-time opts)])
     [(->> [::xt/delete entity-id start-valid-time end-valid-time]
           (filterv some?))])))

(defn idempotent-put-ops
  "Compute transaction operations for put-ing the new entity idempotently.

  This mean that tx-ops are only generated if the new entity does not match the current entity.

  The :atomic? flag also adds a ::xt/match op if the current entity was present."
  [current-entity new-entity & {:keys [atomic? start-valid-time end-valid-time]}]
  (if-not (= current-entity new-entity)
    (let [entity-id (or (:xt/id current-entity) (:xt/id new-entity))
          match-op [::xt/match entity-id current-entity start-valid-time]
          put-op [::xt/put new-entity start-valid-time end-valid-time]]
      (if atomic? [match-op put-op] [put-op]))
    []))

(defn reverse-tx-ops-before-time
  "Scan the entity history and return tx ops that reverse the entity right before the input time.

  This version of the function queries the entity history itself.

  The following options are passed to the generated transaction ops (i.e.: ::xt/put):
    {:start-valid-time java.util.Date
     :end-valid-time   java.util.Date}"
  [xtdb-node entity-id ^Date as-of-time & opts]
  (with-open [history (xt/open-entity-history (xt/db xtdb-node)
                                              entity-id
                                              :desc
                                              {:with-docs? true :with-corrections? true})]
    (-> history
        iterator-seq
        (reverse-history-before-time entity-id as-of-time opts))))

(defn submit-matching-entities
  "Submit a transaction for the entities, atomically.

  Atomicity is implemented this way: if the new entity coincides with
  the one on disk, no transaction is sent over.

  If the entity is differs, it adds a `::xt/match` against the old one
  before inserting. Additionally, it uses `xt/await-tx` for making sure
  we block until a transaction is indexed.

  It returns the transaction returned by `xt/submit-tx` or `nil` if
  nothing no transaction was submitted.

  Shout out @emccue:
    https://clojurians.slack.com/archives/CG3AM2F7V/p1638606871304400?thread_ts=1638590188.303400&cid=CG3AM2F7V

  The optional `opts` map is:
    {:start-valid-time java.util.Date
     :end-valid-time   java.util.Date}

  Note that the same time option (i.e.: start-valid-time) is applied to
  each entity."
  [xtdb-node entities & {:keys [start-valid-time] :as opts}]
  (when (seq entities)
    (let [entity-ids (mapv :xt/id entities)
          results (-> (xt/db xtdb-node start-valid-time)
                      (xt/q '{:find [(pull ?e [*])] :where [[?e :xt/id ?id]] :in [[?id ...]]}
                            entity-ids))
          current-entity-by-id (->> results
                                    (map first)
                                    (group-by :xt/id))
          opts (assoc opts :atomic? true)
          tx-ops (into []
                       (mapcat #(idempotent-put-ops (-> current-entity-by-id
                                                        (get (:xt/id %))
                                                        first)
                                                    %
                                                    opts))
                       entities)]
      (when (seq tx-ops)
        (let [tx (xt/submit-tx xtdb-node tx-ops)]
          (xt/await-tx xtdb-node tx)
          tx)))))

(defn submit-matching-entity
  "Submit a transaction with the new entity atomically.

  Atomicity is implemented this way: if the new entity coincides with
  the one on disk, no transaction is sent over and `nil` is returned.

  Otherwise this function returns the transaction returned by
  `xt/submit-tx`.

  Shout out @emccue:
    https://clojurians.slack.com/archives/CG3AM2F7V/p1638606871304400?thread_ts=1638590188.303400&cid=CG3AM2F7V

  The optional `opts` map is:
    {:start-valid-time java.util.Date
     :end-valid-time   java.util.Date}"
  [xtdb-node new-entity & {:as opts}]
  (submit-matching-entities xtdb-node [new-entity] opts))
