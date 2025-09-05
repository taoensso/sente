(ns taoensso.sente-tests
  "Due to the complexity of automated browser tests, Sente has
  traditionally been tested manually. Before each release, a suite
  of checks have been done against the reference example project.

  In the hope of eventually doing more of this work automatically,
  the current namespace provides a groundwork for future automated
  tests.

  PRs very welcome if you'd like to contribute to this effort!"

  (:require
   [clojure.test                     :as test :refer [deftest testing is]]
   ;; [clojure.test.check            :as tc]
   ;; [clojure.test.check.generators :as tc-gens]
   ;; [clojure.test.check.properties :as tc-props]
   [clojure.string  :as str]
   [taoensso.encore :as enc]

   ;; :cljs cannot compile taoensso.sente under :nodejs target
   #?(:clj  [taoensso.sente :as sente])

   [taoensso.sente.interfaces      :as i]
   [taoensso.sente.packers.edn     :as ep]
   [taoensso.sente.packers.transit :as tp]
   [taoensso.sente.packers.msgpack :as mp])

  #?(:cljs
     (:require-macros
      [taoensso.sente-tests :refer [get-bench-data]])))

(comment
  (remove-ns      'taoensso.sente-tests)
  (test/run-tests 'taoensso.sente-tests))

;;;;

(defn get-bench-data []
  #_{:a :A :b :B :c "foo", :v (vec (range 128)), :s (set (range 128))}
  {:ids (vec (repeatedly 1024 (fn [] (rand-int 32767))))
   :data
   (vec
     (repeatedly 128
       (fn []
         {:children-types [(enc/uuid-str 8) (enc/uuid-str 8) (enc/uuid-str 8)]
          :description ""
          :tips nil
          :a-url-in-text nil
          :name (enc/uuid-str)
          :parents-ids nil
          :type-id 1
          :experimental false
          :duration 61
          :warnings nil
          :a-simple-string (enc/uuid-str 16)
          :a-double-number (rand 32767)
          :a-boolean-i-think-cant-recall nil
          :archive-date nil
          :another-number (rand-int 32767)
          :internal-notes nil
          :id (rand-int 32767)
          :a-boolean false
          :submit-date  (str (enc/now-inst))
          :display-name (enc/uuid-str)
          :user nil
          :a-vector-of-integers (vec (repeatedly 64 (fn [] (rand-int 128))))
          :children-count (rand-int 1024)
          :a-usually-absent-string nil})))})

(defn packed-len [x]
  #?(:cljs (if (instance? js/Uint8Array x) (.-length x) (count x))
     :clj (count x)))

(defn bench1 [packer laps data]
  {:time (enc/qb laps (i/pack packer nil data (fn [x] (i/unpack packer nil (get x :value) (fn [y] (get y :value))))))
   :size (packed-len  (i/pack packer nil data (fn [x]                      (get x :value))))})

(deftest bench-packers
  (let [laps 1e2
        bd (get-bench-data)
        ep (ep/get-packer)
        tp (tp/get-packer)
        mp (mp/get-packer)]

    (println "Benching comparison:"
      {:ep (bench1 ep laps bd)
       :tp (bench1 tp laps bd)
       :mp (bench1 mp laps bd)})))

;;;;

#?(:cljs
   (defmethod test/report [:cljs.test/default :end-run-tests] [m]
     (when-not (test/successful? m)
       ;; Trigger non-zero `lein test-cljs` exit code for CI
       (throw (ex-info "ClojureScript tests failed" {})))))

#?(:cljs (test/run-tests))
