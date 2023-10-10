(ns com.cohesic.xtdb.fixtures
  (:require
   [clojure.java.io :as io]
   [com.cohesic.xtdb.test :as test]
   [xtdb.api :as xt]
   [xtdb.io :as xio]
   [xtdb.mem-kv])
  (:import
   java.nio.file.Files
   java.nio.file.attribute.FileAttribute))

(def ^:dynamic *db-dir-prefix* "cohesic-xtdb-tests")
(def ^:dynamic *kv-opts* {})

(defn with-kv-store
  [f]
  (let [db-dir (.toFile (Files/createTempDirectory *db-dir-prefix* (make-array FileAttribute 0)))]
    (try
      (letfn [(kv-store [db-dir-suffix]
                {:kv-store (merge {:xtdb/module 'xtdb.mem-kv/->kv-store
                                   :sync? true
                                   :db-dir (io/file db-dir db-dir-suffix)}
                                  *kv-opts*)})]
        (with-open [node (xt/start-node {:xtdb/tx-log (kv-store "tx-log")
                                         :xtdb/document-store (kv-store "doc-store")
                                         :xtdb/index-store (kv-store "index-store")})]
          (binding [test/*node* node] (f))))
      (finally (xio/delete-dir db-dir)))))
