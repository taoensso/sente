(ns taoensso.sente-tests
  "Due to the complexity of automated browser tests, Sente has
  traditionally been tested manually. Before each release, a suite
  of checks have been done against the reference example project.

  In the hope of eventually doing more of this work automatically,
  the current namespace provided as a groundwork for future automated
  tests.

  PRs very welcome if you'd like to contribute to this effort!"

  (:require
   [clojure.test                     :as test :refer [deftest testing is]]
   ;; [clojure.test.check            :as tc]
   ;; [clojure.test.check.generators :as tc-gens]
   ;; [clojure.test.check.properties :as tc-props]
   [clojure.string :as str]

   ;; :cljs cannot compile taoensso.sente under :nodejs target
   #?(:clj  [taoensso.sente :as sente])))

(comment
  (remove-ns      'taoensso.sente-tests)
  (test/run-tests 'taoensso.sente-tests))

;;;;

#?(:clj  (deftest _test-clj  (is (= 1 1))))
#?(:cljs (deftest _test-cljs (is (= 1 1))))

;;;;

#?(:cljs (test/run-tests))
