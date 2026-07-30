(ns taoensso.msgpack-tests
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [taoensso.msgpack :as mp]
   [taoensso.msgpack.common :as c]

   #?@(:clj
       [[clojure.test.check.clojure-test :refer [defspec]]
        [clojure.test.check.generators :as gen]
        [clojure.test.check.properties :as prop]])))

(comment
  (remove-ns      'taoensso.msgpack-tests)
  (test/run-tests 'taoensso.msgpack-tests))

;;;;

(defn byte-vec [x]
  #?(:clj
     (when (or (bytes? x)
             (instance? (Class/forName "[Ljava.lang.Byte;") x)
             (instance? (Class/forName "[I") x)
             (instance? (Class/forName "[F") x)
             (instance? (Class/forName "[D") x)) (vec x))

     :cljs
     (cond
       (instance? js/ArrayBuffer x) (vec (js/Array.from (js/Uint8Array. x)))
       (js/ArrayBuffer.isView    x) (vec (js/Array.from (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))))
       (or
         (instance? js/Uint8Array   x)
         (instance? js/Int32Array   x)
         (instance? js/Float32Array x)
         (instance? js/Float64Array x)) (vec (js/Array.from x))
       :else nil)))

(defn unsigned-byte-vec [x]
  (mapv #(bit-and 0xff %) (byte-vec x)))

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

(defn encoded-bytes [xs]
  #?(:clj  (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array. (to-array xs))))

;;;;

(deftest core
  [(rt? nil true false)

   (testing "Ints"
     [(rt? 0 123 -123 1.23 (/ 22 7) 123N 123M)
      (rt? "fixnum" 0x10 0x7f -1 -16 -32)
      (rt? "uint8"  0x80 0xf0 0xff)
      (rt? "uint16" 0x100 0x2000 0xffff)
      (rt? "uint32" 0x10000 0x200000 0xffffffff)
      (rt? "uint64" 0x100000000 0x200000000000 #?(:clj 0xffffffffffffffffN))

      (rt? "int8"  -33 -100 -128)
      (rt? "int16" -129 -2000 -32768)
      (rt? "int32" -32769 -1000000000 -2147483648)
      (rt? "int64" -2147483649 #?(:clj -1000000000000000002) #?(:clj -9223372036854775808))

      (testing "Out-of-bounds ints"
        [(is (thrown? #?(:clj Exception :cljs js/Error) (mp/pack -0x8000000000001000)))
         (is (thrown? #?(:clj Exception :cljs js/Error) (mp/pack 0x10000000000001000)))])])

   (rt? "float32" (float  0.0) (float  2.5))
   (rt? "float64" (double 0.0) (double 2.5) (double 3) 1e-46 1e39 3M)
   #?(:clj
      (is (thrown? Exception (mp/pack 9007199254740993M))))
   #?(:cljs
      (testing "Negative zero"
        (is (js/Object.is (- 0) (rt (- 0))))))

   (rt? "Keywords" :foo :foo/bar)
   (rt? "Symbols"  'foo 'foo/bar)
   (rt? "Strings" "" "foo" "Hi ಬಾ ಇಲ್ಲಿ ಸಂಭವಿಸ 10" \n
     (apply str (repeat 32    \x))
     (apply str (repeat 100   \x))
     (apply str (repeat 255   \x))
     (apply str (repeat 256   \x))
     (apply str (repeat 65535 \x))
     (apply str (repeat 65536 \x)))

   (testing "UTF-8 BOM is string content"
     (is (= (str \uFEFF "a")
           (mp/unpack (encoded-bytes [0xa4 0xef 0xbb 0xbf 0x61])))))

   (testing "Colls"
     [(rt? "Empty"     [] () #{} {})
      (rt? "Non-empty" [1 "2" 3 nil :x [] {}] '(1 "2" 3 nil :x [] {}) #{1 "2" 3 nil :x [] {}} {:a "a", "b" "b", 123 456, nil :x, [] {}})
      #?(:cljs (rt? "Sorted" (sorted-map :a 1 :b 2) (sorted-set 1 2)))
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

   (testing "Primitive num arrays"
     [(rt? #?(:clj (int-array    (range 128)), :cljs (js/Int32Array.   (to-array (range 128)))))
      (rt? #?(:clj (float-array  (range 128)), :cljs (js/Float32Array. (to-array (range 128)))))
      (rt? #?(:clj (double-array (range 128)), :cljs (js/Float64Array. (to-array (range 128)))))

      (testing "use portable big-endian extension bytes"
        [(is (= [0xd6 5 0x01 0x02 0x03 0x04]
               (unsigned-byte-vec
                 (mp/pack #?(:clj  (int-array [0x01020304])
                             :cljs (js/Int32Array. #js [0x01020304]))))))
         (is (= [0xd6 6 0x3f 0x80 0x00 0x00]
               (unsigned-byte-vec
                 (mp/pack #?(:clj  (float-array [1.0])
                             :cljs (js/Float32Array. #js [1.0]))))))
         (is (= [0xd7 7 0x3f 0xf0 0x00 0x00 0x00 0x00 0x00 0x00]
               (unsigned-byte-vec
                 (mp/pack #?(:clj  (double-array [1.0])
                             :cljs (js/Float64Array. #js [1.0]))))))])])

   (testing "Ext types"
     [(is (let [ub (ubytes     1)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     2)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     4)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes     8)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes    16)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes   255)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes   256)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))
      (is (let [ub (ubytes 65536)] (eq ub (:ba-content (rt (c/->PackableExt 106 ub))))))

      (testing "type ids must be signed bytes"
        [(doseq [byte-id [-128 127]]
           (let [ub     (ubytes 1)
                 result (rt (c/->PackableExt byte-id ub))]
             (is (= byte-id (:byte-id result)))
             (is (eq ub (:ba-content result)))))
         (is (thrown? #?(:clj Exception :cljs js/Error)
               (mp/pack (c/->PackableExt -129 (ubytes 1)))))
         (is (thrown? #?(:clj Exception :cljs js/Error)
               (mp/pack (c/->PackableExt  128 (ubytes 1)))))
         (is (thrown? #?(:clj Exception :cljs js/Error)
               (mp/pack (c/->PackableExt 1.5 (ubytes 1)))))])])

   #?(:clj  (is (rt? "Timestamps" (java.time.Instant/now) (java.util.Date.) (java.util.Date. -1))))
   #?(:cljs
      [(is (rt? "Timestamps" (js/Date.)))
       (is (thrown? js/Error (mp/pack (js/Date. js/NaN))))])

   (rt? "UUID" #uuid "66d96ab4-9834-40db-a3cd-42b361503ab9")

   (testing "Key caching"
     [(rt? "Basic" [{:a :A} {:a :A}] {:foo {1 {:x :X} 2 {:y :Y}}})
      (rt? ">256    keys" (let [m (into {} (map (fn [n] [(str "key" n) n])) (range 512))] [m m {:a :A :b :B} m]))
      (rt? "Complex keys"
        {:this-is-a-cachable-key  0
         :this/is-a-cachable-key  1
         "this-is-a-cachable-key" 2
         {:this-is-a-cachable-key 3
          :this/is-a-cachable-key 4} 5
         ["complex key" #{:a :b :c}] 6})

      (testing "Invalid reference"
        (is (thrown? #?(:clj Exception :cljs js/Error)
              (mp/unpack
                (encoded-bytes [0x81 0xd4 0x08 0x00 0x01])))))])])

#?(:cljs
   (deftest unsafe-i64
     (testing "Encoded int64 values must fit ClojureScript's safe integer range"
       (let [safe-max
             (js/Uint8Array.
               #js [0xd3 0x00 0x1f 0xff 0xff 0xff 0xff 0xff 0xff])
             safe-u64
             (js/Uint8Array.
               #js [0xcf 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01])
             unsafe
             (js/Uint8Array.
               #js [0xd3 0x00 0x20 0x00 0x00 0x00 0x00 0x00 0x01])]

         (is (= js/Number.MAX_SAFE_INTEGER (mp/unpack safe-max)))
         (is (= 1 (mp/unpack safe-u64)))
         (is (thrown? js/RangeError (mp/unpack unsafe)))))))

(deftest malformed-uuid
  (is (thrown? #?(:clj Exception :cljs js/Error)
        (mp/unpack (encoded-bytes [0xd4 0x09 0x78]))))
  (is (thrown? #?(:clj Exception :cljs js/Error) ; Non-canonical "1-2-3-4-5"
        (mp/unpack (encoded-bytes [0xc7 0x09 0x09 0x31 0x2d 0x32 0x2d 0x33 0x2d 0x34 0x2d 0x35])))))

(deftest bounded-unpacking
  (is (thrown? #?(:clj Exception :cljs js/Error)
        (mp/unpack (encoded-bytes [0x91 0x91 0xc0]) {:max-nesting-depth 1})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
        (mp/unpack (encoded-bytes [0xc0 0xc3]))))

  (testing "Declared lengths cannot allocate or loop beyond available input"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (mp/unpack (encoded-bytes [0xc4 10 1]))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (mp/unpack (encoded-bytes [0xdc 0 2 0xc0]))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (mp/unpack (encoded-bytes [0xde 0 1 0xc0])))))

  (testing "Explicit value and collection limits are enforced"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (mp/unpack (encoded-bytes [0xc4 2 1 2]) {:max-value-bytes 1})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (mp/unpack (encoded-bytes [0x92 0xc0 0xc0]) {:max-collection-items 1})))))

#?(:clj
   (deftest malformed-typed-array
     (is (thrown? Exception (mp/unpack (encoded-bytes [0xd4 0x05 0x00]))))))

(deftest malformed-built-ins
  (doseq [encoded [[0xd5 0x02 0x61 0x62]
                   [0xc7 0x03 0x03 0x01 0x01 0xc0]
                   [0xd5 0x03 0x01 0x00]
                   [0xd4 0x04 0xc0]
                   [0xd5 0x08 0x00 0x00]
                   [0xd4 0xff 0x00]
                   [0xc7 0x0c 0xff 0x00 0x00 0x00 0x00
                    0x7f 0xff 0xff 0xff 0xff 0xff 0xff 0xff]
                   ;; Ratio with float64 numerator (1.0/3)
                   [0xc7 0x0a 0x03 0xcb 0x3f 0xf0 0x00
                    0x00 0x00 0x00 0x00 0x00 0x03]]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (mp/unpack (encoded-bytes encoded))))))

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
