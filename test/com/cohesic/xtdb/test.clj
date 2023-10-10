(ns com.cohesic.xtdb.test
  (:require
   [clojure.test :as test]
   [xtdb.api :as xt])
  (:import
   xtdb.api.IXtdb))

(def ^:dynamic ^IXtdb *node*)

(defn random-person
  []
  {:xt/id (random-uuid)
   :person/name (rand-nth ["Giovanni" "Pietro" "Sergio" "Alessio" "Danilo"])
   :person/last-name (rand-nth ["Giovannone" "Pietrone" "Sergione" "Alessione" "Danilone"])
   :person/age (rand-int 100)})

(defn is-committed? [node tx] (test/is (xt/tx-committed? node tx)))

(defn result-set [results] (into #{} (map first) results))
