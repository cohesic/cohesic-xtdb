(ns com.cohesic.xtdb-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.cohesic.xtdb :as sut]
   [xtdb.api :as xt])
  (:import
   java.util.Date
   java.util.UUID))

(deftest history-item->put
  (let [start-valid-time (Date.)
        history-item #::xt{:tx-time #inst "2022-08-11T22:39:28.003-00:00"
                      :tx-id 568
                      :valid-time start-valid-time
                      :content-hash #xtdb/id "e48363e8e373c727ff78dda41768cc48c836f2c2"
                      :doc {:xt/id "foo" :foo/bar "quuz" :cohesic/type :foo}}]
    (testing "smoke test"
      (is (= [::xt/put {:foo/bar "quuz" :cohesic/type :foo :xt/id "foo"} start-valid-time]
             (sut/history-item->put history-item))))
    (testing "override start valid time"
      (let [time (Date.)]
        (is (= [::xt/put {:xt/id "foo" :foo/bar "quuz" :cohesic/type :foo} time]
               (sut/history-item->put history-item :start-valid-time time)))))
    (testing "override end valid time"
      (let [time (Date.)]
        (is (= [::xt/put {:xt/id "foo" :foo/bar "quuz" :cohesic/type :foo} start-valid-time time]
               (sut/history-item->put history-item :end-valid-time time)))))
    (testing "no document"
      (is (thrown? AssertionError
                   (-> history-item
                       (dissoc ::xt/doc)
                       (sut/history-item->put)))))))

(deftest history-item->match
  (let [start-valid-time (Date.)
        entity-id "foo"
        history-item #::xt{:tx-time #inst "2022-08-11T22:39:28.003-00:00"
                      :tx-id 568
                      :valid-time start-valid-time
                      :content-hash #xtdb/id "e48363e8e373c727ff78dda41768cc48c836f2c2"
                      :doc {:xt/id entity-id :foo/bar "quuz" :cohesic/type :foo}}]
    (testing "smoke test"
      (is
       (= [::xt/match entity-id {:foo/bar "quuz" :cohesic/type :foo :xt/id "foo"} start-valid-time]
          (sut/history-item->match history-item
                                   :entity-id
                                   entity-id))))
    (testing "override start valid time"
      (let [time (Date.)]
        (is (= [::xt/match entity-id {:foo/bar "quuz" :cohesic/type :foo :xt/id "foo"} time]
               (sut/history-item->match history-item
                                        :entity-id entity-id
                                        :start-valid-time time)))))
    (testing "no document"
      (is (= [:xtdb.api/match entity-id nil start-valid-time]
             (sut/history-item->match
              (assoc history-item ::xt/doc nil)
              :entity-id
              entity-id))))
    (testing "nil entity id"
      (is (thrown? AssertionError
                   (-> history-item
                       (dissoc ::xt/doc)
                       (sut/history-item->match)))))))

(deftest reverse-history-before-no-history
  (testing "given no history for the entity"
    (let [entity-id (UUID/randomUUID)
          history []
          as-of #inst "2022-08"
          tx-ops (sut/reverse-history-before-time history entity-id as-of)
          reverse-delete (some #(when (sut/delete-tx-op? %) %) tx-ops)]
      (is (some? reverse-delete)
          "a reverse ::xt/delete tx-op should be returned")
      (is
       (= entity-id (sut/tx-op-entity-id reverse-delete))
       "the reverse ::xt/delete tx-op's entity should match the entity at the initial valid time"))))

(deftest reverse-history-before-time-no-history-at-time
  (testing "given an entity valid from July 2022 that was updated in October 2022"
    (let [entity-id (UUID/randomUUID)
          start-valid-time #inst "2022-07"
          update-valid-time #inst "2022-10"
          entity1 {:xt/id entity-id :foo/attr 42}
          entity2 {:xt/id entity-id :foo/attr 43}
          history-item1 #::xt{:tx-time (Date.)
                         :tx-id 1
                         :valid-time start-valid-time
                         :content-hash "foo"
                         :doc entity1}
          history-item2 #::xt{:tx-time (Date.)
                         :tx-id 2
                         :valid-time update-valid-time
                         :content-hash "bar"
                         :doc entity2}
          ;; note history has to be in descending order
          history [history-item2 history-item1]]
      (testing "when I want to revert it to any date when the entity did not exist"
        (let [as-of #inst "2022-01"
              tx-ops (sut/reverse-history-before-time history entity-id as-of)
              reverse-delete (some #(when (sut/delete-tx-op? %) %) tx-ops)]
          (is (some? reverse-delete)
              "a reverse ::xt/delete tx-op should be returned")
          (is
           (= entity-id (sut/tx-op-entity-id reverse-delete))
           "the reverse ::xt/delete tx-op's entity should match the entity at the initial valid time"))))))

(deftest reverse-history-before-time-existing-entities
  (testing "given an entity valid from July 2022 that was updated in October 2022"
    (let [entity-id (UUID/randomUUID)
          start-valid-time #inst "2022-07"
          update-valid-time #inst "2022-10"
          entity1 {:xt/id entity-id :foo/attr 42}
          entity2 {:xt/id entity-id :foo/attr 43}
          history-item1 #::xt{:tx-time (Date.)
                         :tx-id 1
                         :valid-time start-valid-time
                         :content-hash "foo"
                         :doc entity1}
          history-item2 #::xt{:tx-time (Date.)
                         :tx-id 2
                         :valid-time update-valid-time
                         :content-hash "bar"
                         :doc entity2}
          ;; note history has to be in descending order
          history [history-item2 history-item1]]
      (testing "when I want to revert it to August 2022"
        (let [as-of #inst "2022-08"
              tx-ops (sut/reverse-history-before-time history entity-id as-of)
              match (some #(when (sut/match-tx-op? %) %) tx-ops)
              put (some #(when (sut/put-tx-op? %) %) tx-ops)]
          (is
           (= entity1 (sut/tx-op-entity put))
           "the reverse ::xt/put tx-op's entity should match the entity at the initial valid time")
          (is
           (= start-valid-time (sut/tx-op-start-valid-time put))
           "the reverse ::xt/put tx-op's valid time should match the entity initial valid time")
          (is
           (= entity2 (sut/tx-op-entity match))
           "the reverse ::xt/match tx-op's entity should match the updated entity")
          (is
           (= update-valid-time (sut/tx-op-start-valid-time match))
           "the reverse ::xt/match tx-op's valid time should match the update valid time"))))))

(deftest reverse-history-before-time-with-deleted-entities
  (testing "Given an entity valid at point-in-time-A that was deleted at point-int-time-B"
    (let [entity-id (UUID/randomUUID)
          start-valid-time #inst "2022-07"
          delete-valid-time #inst "2022-10"
          entity1 {:xt/id entity-id :foo/attr 616}
          history-item1 #::xt{:tx-time (Date.)
                         :tx-id 1
                         :valid-time start-valid-time
                         :content-hash "foo"
                         :doc entity1}
          history-item2 #::xt{:tx-time (Date.)
                         :tx-id 2
                         :valid-time delete-valid-time
                         :content-hash "0000000000000000000000000000000000000000"
                         :doc nil}
          ;; note history has to be in descending order
          history [history-item2 history-item1]]
      (testing "The entity is available when I revert to point-in-time-A"
        (let [as-of #inst "2022-08"
              tx-ops (sut/reverse-history-before-time history entity-id as-of)
              put (some #(when (sut/put-tx-op? %) %) tx-ops)]
          (is
           (= entity1 (sut/tx-op-entity put))
           "the reverse ::xt/put tx-op's entity should match the entity at the initial valid time")
          (is
           (= start-valid-time (sut/tx-op-start-valid-time put))
           "the reverse ::xt/put tx-op's valid time should match the entity initial valid time"))))))

(deftest idempotent-put-ops-current-entity-missing
  (testing "atomic? false"
    (let [entity-id (UUID/randomUUID)
          current-entity nil
          new-entity {:xt/id entity-id :foo/attr 43}
          tx-ops (sut/idempotent-put-ops current-entity new-entity)]
      (testing "put transaction"
        (let [put (some #(when (sut/put-tx-op? %) %) tx-ops)]
          (is (= new-entity (sut/tx-op-entity put)))))
      (testing "match transaction"
        (is (nil? (some #(when (sut/match-tx-op? %) %) tx-ops))
            "should be missing"))))
  (testing "atomic? true"
    (let [entity-id (UUID/randomUUID)
          current-entity nil
          new-entity {:xt/id entity-id :foo/attr 43}
          tx-ops (sut/idempotent-put-ops current-entity new-entity {:atomic? true})]
      (testing "put transaction"
        (let [put (some #(when (sut/put-tx-op? %) %) tx-ops)]
          (is (= new-entity (sut/tx-op-entity put)))))
      (testing "match transaction"
        (let [match (some #(when (sut/match-tx-op? %) %) tx-ops)]
          (is (= current-entity (sut/tx-op-entity match))))))))

(deftest idempotent-put-ops-current-entity-present
  (testing "atomic? false"
    (let [entity-id (UUID/randomUUID)
          current-entity {:xt/id entity-id :foo/attr 42}
          new-entity {:xt/id entity-id :foo/attr 43}
          tx-ops (sut/idempotent-put-ops current-entity new-entity)]
      (testing "put transaction"
        (let [put (some #(when (sut/put-tx-op? %) %) tx-ops)]
          (is (= new-entity (sut/tx-op-entity put)))))
      (testing "match transaction"
        (is (nil? (some #(when (sut/match-tx-op? %) %) tx-ops))
            "should be missing")))

    (testing "atomic? true"
      (let [entity-id (UUID/randomUUID)
            current-entity {:xt/id entity-id :foo/attr 42}
            new-entity {:xt/id entity-id :foo/attr 43}
            tx-ops (sut/idempotent-put-ops current-entity new-entity {:atomic? true})]
        (testing "put transaction"
          (let [put (some #(when (sut/put-tx-op? %) %) tx-ops)]
            (is (= new-entity (sut/tx-op-entity put)))))
        (testing "match transaction"
          (let [match (some #(when (sut/match-tx-op? %) %) tx-ops)]
            (is (= current-entity (sut/tx-op-entity match)))))))))
