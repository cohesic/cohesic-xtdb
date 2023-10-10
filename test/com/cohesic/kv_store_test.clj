(ns com.cohesic.kv-store-test
  (:require
   [clojure.test :as test :refer [deftest is testing use-fixtures]]
   [com.cohesic.xtdb :as sut]
   [com.cohesic.xtdb.fixtures :as fixtures]
   [com.cohesic.xtdb.test :as xtest]
   [java-time.api :as jtime]
   [xtdb.api :as xt]))

(use-fixtures :each fixtures/with-kv-store)

(deftest submit-matching-entity-sanity-check
  (let [node xtest/*node*
        p1 (xtest/random-person)]
    (testing "submitting against empty database"
      (xtest/is-committed? node (sut/submit-matching-entity node p1))
      (is (= (:xt/id p1)
             (-> (xt/db node)
                 (xt/q {:find '[e] :where [['e :person/name (:person/name p1)]]})
                 ffirst))))))

(deftest submit-matching-entities-empty-entities
  (let [node xtest/*node* entities []] (is (nil? (sut/submit-matching-entities node entities)))))

(deftest submit-matching-entities-sanity-check
  (let [node xtest/*node*
        p1 (xtest/random-person)
        p2 (xtest/random-person)
        entities [p1 p2]
        entity-ids (into #{} (map :xt/id entities))]
    (testing "submitting against empty database"
      (xtest/is-committed? node (sut/submit-matching-entities node entities))
      (is (= entity-ids
             (-> (xt/db node)
                 (xt/q '{:find [e] :in [[?id ...]] :where [[e :xt/id ?id]]}
                       entity-ids)
                 (xtest/result-set)))))
    (testing "submitting against populated database"
      (testing "changing a document"
        (let [p1-last-name "Foo"
              p1-bis (assoc p1 :person/last-name p1-last-name)
              entities [p1-bis]]
          (xtest/is-committed? node (sut/submit-matching-entities node entities))
          (is (= (:xt/id p1-bis)
                 (-> (xt/db node)
                     (xt/q {:find '[e] :where [['e :person/last-name p1-last-name]]})
                     ffirst)))))
      (testing "submitting a document unchanged"
        (let [entities [p2]]
          (is (nil? (sut/submit-matching-entities node entities))
              "the function returns `nil` when there is nothing to do"))))))

(deftest submit-matching-entities-at-start-valid-time
  (let [node xtest/*node*
        p1 (xtest/random-person)
        p2 (xtest/random-person)
        entities [p1 p2]
        entity-ids (into #{} (map :xt/id entities))]
    (testing "submitting entities at :start-valid-time"
      (let [start-valid-time (jtime/instant)]
        (xtest/is-committed? node
                             (sut/submit-matching-entities
                              node
                              entities
                              :start-valid-time
                              (jtime/java-date start-valid-time)))
        (testing "fetching before :start-valid-time"
          (let [before-start-time (jtime/minus start-valid-time (jtime/days 1))]
            (is (empty?
                 (-> (xt/db node (jtime/java-date before-start-time))
                     (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                           (mapv :person/name entities))))
                "nothing shows up before :start-valid-time")))
        (testing "fetching after :start-valid-time"
          (let [after-start-time (jtime/plus start-valid-time (jtime/days 1))]
            (is (= entity-ids
                   (-> (xt/db node (jtime/java-date after-start-time))
                       (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                             (mapv :person/name entities))
                       (xtest/result-set))))))))))

(deftest submit-matching-entities-at-start+end-valid-time
  (let [node xtest/*node*
        p1 (xtest/random-person)
        p2 (xtest/random-person)
        entities [p1 p2]
        entity-ids (into #{} (map :xt/id entities))]
    (testing "submitting entities at :start-valid-time *and* :end-valid-time"
      (let [start-valid-time (jtime/instant)
            end-valid-time (jtime/plus (jtime/instant) (jtime/days 3))]
        (println end-valid-time)
        (xtest/is-committed? node
                             (sut/submit-matching-entities
                              node
                              entities
                              :start-valid-time (jtime/java-date start-valid-time)
                              :end-valid-time (jtime/java-date end-valid-time)))
        (testing "fetching before :start-valid-time"
          (let [before-start-time (jtime/minus start-valid-time (jtime/days 1))]
            (is (empty?
                 (-> (xt/db node (jtime/java-date before-start-time))
                     (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                           (mapv :person/name entities))))
                "nothing shows up after :end-valid-time")))
        (testing "fetching after :start-valid-time"
          (let [after-start-time (jtime/plus start-valid-time (jtime/days 1))]
            (is (= entity-ids
                   (-> (xt/db node (jtime/java-date after-start-time))
                       (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                             (mapv :person/name entities))
                       (xtest/result-set)))
                "nothing shows up after :end-valid-time")))
        (testing "fetching before :end-valid-time"
          (let [before-end-time (jtime/plus (jtime/instant) (jtime/days 2))]
            (is (= entity-ids
                   (-> (xt/db node (jtime/java-date before-end-time))
                       (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                             (mapv :person/name entities))
                       (xtest/result-set))))))
        (testing "fetching after :end-valid-time"
          (let [after-end-time (jtime/plus end-valid-time (jtime/days 1))]
            (is (empty?
                 (-> (xt/db node (jtime/java-date after-end-time))
                     (xt/q '{:find [e] :in [[?name ...]] :where [[e :person/name ?name]]}
                           (mapv :person/name entities))))
                "nothing shows up after :end-valid-time")))))))
