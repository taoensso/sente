(ns taoensso.sente.tests.main
  (:require [expectations   :as test :refer :all]
            [taoensso.sente :as sente  :refer ()]))

(comment (test/run-tests '[taoensso.sente.tests.main]))

(defn- before-run {:expectations-options :before-run} [])
(defn- after-run  {:expectations-options :after-run}  [])
