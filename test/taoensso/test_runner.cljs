(ns taoensso.test-runner
  (:require
   [cljs.test :as test]
   [taoensso.express-tests]
   [taoensso.msgpack-tests]
   [taoensso.sente-tests]))

(defmethod test/report [:cljs.test/default :end-run-tests] [m]
  (when-not (test/successful? m)
    (throw (ex-info "ClojureScript tests failed" {}))))

(test/run-tests
  'taoensso.express-tests
  'taoensso.msgpack-tests
  'taoensso.sente-tests)
