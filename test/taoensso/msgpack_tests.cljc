(ns taoensso.msgpack-tests
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [taoensso.msgpack :as mp]
   [taoensso.msgpack.interfaces :as i]

   #?@(:clj
       [[clojure.test.check.clojure-test :refer [defspec]]
        [clojure.test.check.generators :as gen]
        [clojure.test.check.properties :as prop]])))

(comment
  (remove-ns      'taoensso.msgpack-tests)
  (test/run-tests 'taoensso.msgpack-tests))

;;;;

(defn byte-vec [x]
  #?(:clj (when (or (bytes? x) (instance? (Class/forName "[Ljava.lang.Byte;") x)) (vec x))
     :cljs
     (cond
       (instance? js/Uint8Array  x) (vec (js/Array.from x))
       (instance? js/ArrayBuffer x) (vec (js/Array.from (js/Uint8Array. x)))
       (js/ArrayBuffer.isView    x) (vec (js/Array.from (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))))
       :else nil)))

(defn eq
  ([x y] (= (eq x) (eq y)))
  ([x  ]
   (or
     (byte-vec x)
     #?(:clj (when (instance? java.util.Date       x) (.toInstant x)))
     #?(:clj (when (instance? java.math.BigDecimal x) (double     x)))
     x)))

(defn rt  "Roundtrip"   [x] (mp/unpack (mp/pack x)))
(defn rt? "Roundtrips?" [& xs] (mapv #(is (eq % (rt %)) (str {:type (type %), :value %})) xs))

(defn ubytes [n]
  #?(:clj  (doto (byte-array     n) (java.util.Arrays/fill (byte 1)))
     :cljs (doto (js/Uint8Array. n)                 (.fill       1))))

;;;;

(deftest core
  [(rt? nil true false)

   (testing "Ints"
     [(rt? 0 123 -123 1.23 (/ 22 7) 123N 123M)
      (rt? "fixnum" 0x10 0x7f -1 -16 -32)
      (rt? "uint8"  0x80 0xf0 0xff)
      (rt? "uint16" 0x100 0x2000 0xffff)
      (rt? "uint32" 0x10000 0x200000 0xffffffff)
      (rt? "uint64" 0x100000000 0x200000000000 #?(:cljs 0xffffffffffffffff))

      (rt? "int8"  -33 -100 -128)
      (rt? "int16" -129 -2000 -32768)
      (rt? "int32" -32769 -1000000000 -2147483648)
      (rt? "int64" -2147483649 -1000000000000000002 -9223372036854775808)

      (testing "Out-of-bounds ints"
        [(is (thrown? #?(:clj Exception :cljs js/Error) (mp/pack -0x8000000000001000)))
         (is (thrown? #?(:clj Exception :cljs js/Error) (mp/pack 0x10000000000001000)))])])

   (rt? "float32" (float  0.0) (float  2.5))
   (rt? "float64" (double 0.0) (double 2.5) (double 3) 1e-46 1e39 3M)

   (rt? "Keywords" :foo :foo/bar)
   (rt? "Symbols"  'foo 'foo/bar)
   (rt? "Strings" "" "foo" "Hi ಬಾ ಇಲ್ಲಿ ಸಂಭವಿಸ 10" \n
     (apply str (repeat 32    \x))
     (apply str (repeat 100   \x))
     (apply str (repeat 255   \x))
     (apply str (repeat 256   \x))
     (apply str (repeat 65535 \x))
     (apply str (repeat 65536 \x)))

   (testing "Colls"
     [(rt? "Empty"     [] () #{} {})
      (rt? "Non-empty" [1 "2" 3 nil :x [] {}] '(1 "2" 3 nil :x [] {}) #{1 "2" 3 nil :x [] {}} {:a "a", "b" "b", 123 456, nil :x, [] {}})
      (let [c (range 0 (inc   256))] (rt? "size8"  c (vec c) (set c) (zipmap c c)))
      (let [c (range 0 (inc 65535))] (rt? "size16" c (vec c) (set c) (zipmap c c)))
      (let [c (range 0 (inc 65536))] (rt? "size32" c (vec c) (set c) (zipmap c c)))])

   (testing "Byte arrays"
     [(rt? "bin8"  (ubytes 0))
      (rt? "bin8"  (ubytes 1))
      (rt? "bin8"  (ubytes 32))
      (rt? "bin8"  (ubytes 255))
      (rt? "bin16" (ubytes 256))
      (rt? "bin32" (ubytes 65536))])

   (testing "Ext types"
     [(is (let [ub (ubytes     1)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     2)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     4)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     8)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes    16)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes   255)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes   256)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))
      (is (let [ub (ubytes 65536)] (eq ub (:ba-content (rt (i/->PackableExt 106 ub))))))])

   #?(:clj  (is (rt? "Timestamps" (java.time.Instant/now) (java.util.Date.) (java.util.Date. -1))))
   #?(:cljs (is (rt? "Timestamps" (js/Date.))))])

#?(:cljs
   (defmethod test/report [:cljs.test/default :end-run-tests] [m]
     (when-not (test/successful? m)
       (throw (ex-info "ClojureScript tests failed" {})))))

#?(:cljs (test/run-tests))

;;;; test.check

#?(:clj
   (do
     (defspec   strs-rt 100 (prop/for-all [x gen/string] (= (rt x) x)))
     (defspec   ints-rt 100 (prop/for-all [x gen/int]                                            (=  (rt x) x)))
     (defspec floats-rt 100 (prop/for-all [x (gen/such-that #(not (Double/isNaN %)) gen/double)] (=  (rt x) x)))
     (defspec  bytes-rt 100 (prop/for-all [x gen/bytes]                                          (eq (rt x) x)))
     (defspec bbytes-rt 100 (prop/for-all [x gen/bytes] (let [x (into-array Byte x)]             (eq (rt x) x)))) ; Boxed bytes
     (defspec   maps-rt 100 (prop/for-all [x             (gen/map gen/string  gen/int)]          (=  (rt x) x)))
     (defspec   vecs-rt 100 (prop/for-all [x (gen/vector (gen/map gen/int gen/string))]          (=  (rt x) x)))))
