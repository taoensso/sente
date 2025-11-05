(ns taoensso.msgpack.impl
  (:require
   [goog.math.Long]
   [taoensso.truss  :as truss]
   [taoensso.encore :as enc]
   [taoensso.msgpack.common :as c
    :refer [Packable PackableExt pack-bytes CachedKey]]))

;;;; Streams

(defprotocol IInputStream
  (read-u8s [_ n])
  (read-str [_ n])
  (read-u8  [_])
  (read-i8  [_])
  (read-u16 [_])
  (read-i16 [_])
  (read-u32 [_])
  (read-i32 [_])
  (read-i64 [_])
  (read-f32 [_])
  (read-f64 [_]))

(defprotocol IOutputStream
  (expand     [_ n])
  (write-u8s* [_ in])
  (write-u8s  [_ u8s])
  (write-u8   [_ u8])
  (write-i8   [_ i8])
  (write-u16  [_ u16])
  (write-i16  [_ i16])
  (write-u32  [_ u32])
  (write-i32  [_ i32])
  (write-f32  [_ f32])
  (write-i64  [_ i64])
  (write-f64  [_ f64]))

(defn- as-u8s
  "#{Uint8Array ArrayBuffer DataView} -> Uint8Array"
  ^js [input]
  (cond
    (instance? js/Uint8Array  input)                 input
    (instance? js/ArrayBuffer input) (js/Uint8Array. input)
    (instance? js/DataView    input) (js/Uint8Array. (.-buffer     input)
                                                     (.-byteOffset input)
                                                     (.-byteLength input))
    :else
    (truss/ex-info! "Unexpected input type"
      {:type (enc/type-name input)
       :expected '#{Uint8Array ArrayBuffer DataView}})))

(def ^:private ^:const max-safe-int js/Number.MAX_SAFE_INTEGER)
(def ^:private ^:const min-safe-int (- max-safe-int))

(def ^:private text-decoder (js/TextDecoder. "utf-8"))
(def ^:private text-encoder (js/TextEncoder.))

(deftype InputStream
  [    ^:unsynchronized-mutable offset
   ^js ^:unsynchronized-mutable view
   ^js ^:unsynchronized-mutable u8s]

  IDeref (-deref [_] u8s)
  IInputStream
  (read-u8s [this n]
    (let [i     offset
          end   (+ i n)
          chunk (.subarray u8s i end)]
      (set! offset end)
      chunk))

  (read-str [this n] (.decode text-decoder (read-u8s this n)))
  (read-u8  [this] (let [i offset, v (.getUint8   view i)] (set! offset (+ i 1)) v))
  (read-i8  [this] (let [i offset, v (.getInt8    view i)] (set! offset (+ i 1)) v))
  (read-u16 [this] (let [i offset, v (.getUint16  view i)] (set! offset (+ i 2)) v))
  (read-i16 [this] (let [i offset, v (.getInt16   view i)] (set! offset (+ i 2)) v))
  (read-u32 [this] (let [i offset, v (.getUint32  view i)] (set! offset (+ i 4)) v))
  (read-i32 [this] (let [i offset, v (.getInt32   view i)] (set! offset (+ i 4)) v))
  (read-f32 [this] (let [i offset, v (.getFloat32 view i)] (set! offset (+ i 4)) v))
  (read-f64 [this] (let [i offset, v (.getFloat64 view i)] (set! offset (+ i 8)) v))
  (read-i64 [this]
    (let [i  offset
          hi (.getInt32  view    i)
          lo (.getUint32 view (+ i 4))]
      (set! offset (+ i 8))
      (.toNumber (goog.math.Long.fromBits lo hi)))))

(deftype OutputStream
  [    ^:unsynchronized-mutable offset
   ^js ^:unsynchronized-mutable view
   ^js ^:unsynchronized-mutable u8s]

  IDeref (-deref [_] (.subarray u8s 0 offset))
  IOutputStream
  (expand [_ n]
    (let [i offset, need (+ i n)]
      (when   (> need (.-byteLength u8s))
        (let [old-buf (.-buffer     u8s)
              old-len (.-byteLength u8s)
              new-len (long (max need (* 2 old-len)))
              new-u8s (js/Uint8Array.      new-len)]
          (.set     new-u8s (.subarray u8s 0 i) 0)
          (set! u8s new-u8s)
          (set! view (js/DataView. (.-buffer new-u8s)))))))

  (write-u8s* [this          in] (write-u8s this (as-u8s in)))
  (write-u8s  [this ^js src-u8s]
    (let [i offset, n (.-byteLength ^js src-u8s)]
      (expand this n)
      (.set ^js u8s src-u8s i)
      (set! offset (+ i n))))

  (write-u8  [this n] (expand this 1) (let [i offset] (.setUint8   view i n) (set! offset (+ i 1))))
  (write-i8  [this n] (expand this 1) (let [i offset] (.setInt8    view i n) (set! offset (+ i 1))))
  (write-u16 [this n] (expand this 2) (let [i offset] (.setUint16  view i n) (set! offset (+ i 2))))
  (write-i16 [this n] (expand this 2) (let [i offset] (.setInt16   view i n) (set! offset (+ i 2))))
  (write-u32 [this n] (expand this 4) (let [i offset] (.setUint32  view i n) (set! offset (+ i 4))))
  (write-i32 [this n] (expand this 4) (let [i offset] (.setInt32   view i n) (set! offset (+ i 4))))
  (write-f32 [this n] (expand this 4) (let [i offset] (.setFloat32 view i n) (set! offset (+ i 4))))
  (write-f64 [this n] (expand this 8) (let [i offset] (.setFloat64 view i n) (set! offset (+ i 8))))
  (write-i64 [this n]
    (let [gl (goog.math.Long.fromNumber n)]
      (write-i32 this (.getHighBits gl))
      (write-i32 this (.getLowBits  gl)))))

(defn- in-stream
  "Uint8Array -> InputStream"
  [^js u8s]
  (let [ab  (.-buffer     u8s)
        off (.-byteOffset u8s)
        len (.-byteLength u8s)
        ^js view (js/DataView. ab off len)]
    (InputStream. 0 view u8s)))

(defn- in-stream*
  "#{Uint8Array ArrayBuffer DataView subs} -> InputStream"
  [input] (in-stream (as-u8s input)))

(defn- out-stream
  "Returns OutputStream of given size"
  [size]
  (let [^js ab (js/ArrayBuffer. size)]
    (OutputStream. 0
      ^js (js/DataView.   ab)
      ^js (js/Uint8Array. ab))))

(defn- out-stream*
  "#{Uint8Array ArrayBuffer DataView subs} -> OutputStream"
  [output]
  (if (instance? OutputStream output)
    output
    (let [^js u8s (as-u8s output)
          ab   (.-buffer     u8s)
          off  (.-byteOffset u8s)
          len  (.-byteLength u8s)
          ^js view (js/DataView. ab off len)]
      (OutputStream. 0 view u8s))))

;;;; Packing

(defn- pack-u8s [^js u8s out]
  (let [len (.-byteLength u8s)]
    (cond
      (<= len 0xff)       (do (write-u8 out 0xc4) (write-u8  out len) (write-u8s out u8s))
      (<= len 0xffff)     (do (write-u8 out 0xc5) (write-u16 out len) (write-u8s out u8s))
      (<= len 0xffffffff) (do (write-u8 out 0xc6) (write-u32 out len) (write-u8s out u8s)))))

(defn- pack-str [^string s out]
  (let [^js u8s (.encode text-encoder s)
        len (.-byteLength u8s)]
    (cond
      (<= len 0x1f)       (do (write-u8 out   (bit-or 2r10100000 len)) (write-u8s out u8s))
      (<= len 0xff)       (do (write-u8 out 0xd9) (write-u8  out len)  (write-u8s out u8s))
      (<= len 0xffff)     (do (write-u8 out 0xda) (write-u16 out len)  (write-u8s out u8s))
      (<= len 0xffffffff) (do (write-u8 out 0xdb) (write-u32 out len)  (write-u8s out u8s)))))

(defn- pack-int [n out]
  (if (neg? n)
    (cond
      (>= n -32)                                  (write-i8  out n)  ; -fixnum
      (>= n -0x80)        (do (write-u8 out 0xd0) (write-i8  out n)) ; int8
      (>= n -0x8000)      (do (write-u8 out 0xd1) (write-i16 out n)) ; int16
      (>= n -0x80000000)  (do (write-u8 out 0xd2) (write-i32 out n)) ; int32
      (>= n min-safe-int) (do (write-u8 out 0xd3) (write-i64 out n)) ; int64
      :else (throw (js/RangeError. (str "Int too small to safely pack: " n))))

    (cond
      (<= n 127)                                         (write-u8  out n)  ; +fixnum
      (<= n 0xff)         (do (write-u8 out 0xcc)        (write-u8  out n)) ; uint8
      (<= n 0xffff)       (do (write-u8 out 0xcd)        (write-u16 out n)) ; uint16
      (<= n 0xffffffff)   (do (write-u8 out 0xce)        (write-u32 out n)) ; uint32
      (<= n max-safe-int) (do (write-u8 out 0xd3 #_0xcf) (write-i64 out n)) ;  int64 (no uint64 support)
      :else (throw (js/RangeError. (str "Int too large to safely pack: " n))))))

(let [f32a (js/Float32Array. 1)
      f32-exact? (fn [n] (aset f32a 0 n) (= (aget f32a 0) n))]

  (defn- pack-dbl [n out]
    (if (f32-exact? n)
      (do (write-u8 out 0xca) (write-f32 out n))
      (do (write-u8 out 0xcb) (write-f64 out n)))))

(defn- pack-num  [n out] (if (integer? n) (pack-int n out) (pack-dbl n out)))
(defn- pack-coll [c out] (reduce (fn [_ el] (pack-bytes el out)) nil c))
(defn- pack-seq  [s out]
  (let [len (count s)]
    (cond
      (<= len 0xf)        (do (write-u8 out   (bit-or 2r10010000 len)) (pack-coll s out))
      (<= len 0xffff)     (do (write-u8 out 0xdc) (write-u16 out len)  (pack-coll s out))
      (<= len 0xffffffff) (do (write-u8 out 0xdd) (write-u32 out len)  (pack-coll s out)))))

#_(defn- pack-kvs [m out] (reduce-kv (fn [_ k v] (pack-bytes k  out) (pack-bytes v out)) nil m))
(defn-   pack-kvs [m out]
  (let [key-cache_ c/*key-cache_*]
    (reduce-kv
      (fn [_ k v]
        (if-let [cache-idx (c/key-cache-pack! key-cache_ k)]
          #_(pack-bytes (PackableExt. 8 (js/Uint8Array. #js [cache-idx])) out)
          (do
            (write-u8  out 0xd4)
            (write-u8  out 8)
            (write-u8s out (js/Uint8Array. #js [cache-idx])))
          (pack-bytes k out))
        (pack-bytes   v out))
      nil m)))

(defn- pack-map [m out]
  (let [len (count m)]
    (cond
      (<= len 0xf)        (do (write-u8 out   (bit-or 2r10000000 len)) (pack-kvs m out))
      (<= len 0xffff)     (do (write-u8 out 0xde) (write-u16 out len)  (pack-kvs m out))
      (<= len 0xffffffff) (do (write-u8 out 0xdf) (write-u32 out len)  (pack-kvs m out)))))

(declare pack)

(extend-protocol Packable
  nil                (pack-bytes [_ out] (write-u8    out       0xc0))
  boolean            (pack-bytes [b out] (write-u8    out (if b 0xc3 0xc2)))
  string             (pack-bytes [s out] (pack-str s out))
  number             (pack-bytes [n out] (pack-num n out))
  ;;
  PersistentVector   (pack-bytes [s out] (pack-seq      s  out))
  List               (pack-bytes [s out] (pack-seq      s  out))
  EmptyList          (pack-bytes [s out] (pack-seq      s  out))
  LazySeq            (pack-bytes [s out] (pack-seq (seq s) out))
  ;;
  PersistentArrayMap (pack-bytes [m out] (pack-map      m  out))
  PersistentHashMap  (pack-bytes [m out] (pack-map      m  out))
  ;;
  js/Uint8Array      (pack-bytes [a out] (pack-u8s      a  out))
  ;;
  PackableExt
  (pack-bytes [x out]
    (let [byte-id     (.-byte-id    x)
          u8s-content (.-ba-content x)
          len (.-byteLength u8s-content)]

      (case len
        1  (write-u8 out 0xd4)
        2  (write-u8 out 0xd5)
        4  (write-u8 out 0xd6)
        8  (write-u8 out 0xd7)
        16 (write-u8 out 0xd8)
        (cond
          (<= len 0xff)       (do (write-u8 out 0xc7) (write-u8  out len))
          (<= len 0xffff)     (do (write-u8 out 0xc8) (write-u16 out len))
          (<= len 0xffffffff) (do (write-u8 out 0xc9) (write-u32 out len))))
      (write-u8  out byte-id)
      (write-u8s out u8s-content)))

  default
  (pack-bytes [x out]
    (if (seqable? x)
      (pack-seq x out)
      (let [type-name (enc/type-name x)]
        (truss/ex-info!
          (str "Pack failed: unsupported type (" type-name ")")
          {:type type-name, :value x})))))

;;;; Unpacking

(declare unpack-1)
#_(defn- unpack-n [init n in] (persistent! (reduce (fn [acc _] (conj!  acc (unpack-1 in))) (transient init) (range n))))
(defn-   unpack-n [init n in]
  (if (zero? n)
    init
    (loop [n n, acc (transient init)]
      (if (zero? n)
        (persistent! acc)
        (recur (dec n) (conj! acc (unpack-1 in)))))))

#_(defn- unpack-map [n in] (persistent! (reduce (fn [acc _] (assoc! acc (unpack-1 in) (unpack-1 in))) (transient {}) (range n))))
(defn-   unpack-map [n in]
  (if (zero? n)
    {}
    (let [key-cache_ c/*key-cache_*]
      (loop [n n, acc (transient {})]
        (if (zero? n)
          (persistent! acc)
          (let [k (unpack-1 in)
                k
                (if (instance? CachedKey k)
                  (.-val      ^CachedKey k)
                  (do (c/key-cache-unpack! key-cache_ k) k))]
            (recur (dec n) (assoc! acc k (unpack-1 in)))))))))

(defn- unpack-1 [in]
  (let   [byte-id (read-u8 in)]
    (case byte-id
      0xc0 nil
      0xc2 false
      0xc3 true

      ;; Ints
      0xcc (read-u8  in)
      0xcd (read-u16 in)
      0xce (read-u32 in)
      0xcf (throw (js/RangeError. "Int too large to safely unpack (need u64)"))
      0xd0 (read-i8  in)
      0xd1 (read-i16 in)
      0xd2 (read-i32 in)
      0xd3 (read-i64 in)

      ;; Floats
      0xca (read-f32 in)
      0xcb (read-f64 in)

      ;; Strings
      0xd9 (read-str in (read-u8  in))
      0xda (read-str in (read-u16 in))
      0xdb (read-str in (read-u32 in))

      ;; Byte arrays
      0xc4 (read-u8s in (read-u8  in))
      0xc5 (read-u8s in (read-u16 in))
      0xc6 (read-u8s in (read-u32 in))

      ;; Seqs
      0xdc (unpack-n [] (read-u16 in) in)
      0xdd (unpack-n [] (read-u32 in) in)

      ;; Maps
      0xde (unpack-map (read-u16 in) in)
      0xdf (unpack-map (read-u32 in) in)

      ;; Extensions
      0xd4                        (c/unpack-ext (read-i8 in) (read-u8s in 1))
      0xd5                        (c/unpack-ext (read-i8 in) (read-u8s in 2))
      0xd6                        (c/unpack-ext (read-i8 in) (read-u8s in 4))
      0xd7                        (c/unpack-ext (read-i8 in) (read-u8s in 8))
      0xd8                        (c/unpack-ext (read-i8 in) (read-u8s in 16))
      0xc7 (let [n (read-u8  in)] (c/unpack-ext (read-i8 in) (read-u8s in n)))
      0xc8 (let [n (read-u16 in)] (c/unpack-ext (read-i8 in) (read-u8s in n)))
      0xc9 (let [n (read-u32 in)] (c/unpack-ext (read-i8 in) (read-u8s in n)))

      ;; Fix types
      (cond
        (== (bit-and byte-id 2r10000000) 0)             byte-id        ; +fixnum
        (== (bit-and byte-id 2r11100000) 2r11100000) (- byte-id 0x100) ; -fixnum
        (== (bit-and byte-id 2r11100000) 2r10100000) (read-str in (bit-and 2r11111 byte-id))    ; String
        (== (bit-and byte-id 2r11110000) 2r10010000) (unpack-n [] (bit-and 2r1111  byte-id) in) ; Seq
        (== (bit-and byte-id 2r11110000) 2r10000000) (unpack-map  (bit-and 2r1111  byte-id) in) ; Map
        :else (truss/ex-info! "Unpack failed: unexpected `byte-id`" {:byte-id byte-id})))))

;;;; Built-in extensions

(c/extend-packable 0 Keyword
  (pack   [k]       (.encode text-encoder (.substring (str k) 1)))
  (unpack [^js u8s] (keyword (.decode text-decoder ^js u8s))))

(c/extend-packable 1 Symbol
  (pack   [s]       (.encode text-encoder (str s) 1))
  (unpack [^js u8s] (symbol (.decode text-decoder ^js u8s))))

(c/extend-packable 2 nil ; Char
  nil
  (unpack [^js u8s] (.decode text-decoder ^js u8s)))

(c/extend-packable 3 nil ; Ratio
  nil
  (unpack [^js u8s]
    (let [in (in-stream ^js u8s)] (/ (unpack-1 in) (unpack-1 in)))))

(c/extend-packable 4 PersistentHashSet
  (pack   [s] (let [out (out-stream 1024)] (pack-seq s out) @out))
  (unpack [^js u8s]
    (let [in (in-stream u8s)]
      (let    [byte-id (read-u8 in)]
        (case  byte-id
          0xdc (unpack-n #{} (read-u16 in)            in)
          0xdd (unpack-n #{} (read-u32 in)            in)
          (do  (unpack-n #{} (bit-and 2r1111 byte-id) in)))))))

(c/extend-packable 5 js/Int32Array
  (pack   [a] (js/Uint8Array. (.-buffer a) (.-byteOffset a) (.-byteLength a)))
  (unpack [^js u8s]
    (let [^js copy (js/Uint8Array. u8s)]
      (js/Int32Array. (.-buffer copy) 0 (quot (.-byteLength copy) 4)))))

(c/extend-packable 6 js/Float32Array
  (pack   [a] (js/Uint8Array. (.-buffer a) (.-byteOffset a) (.-byteLength a)))
  (unpack [^js u8s]
    (let [^js copy (js/Uint8Array. u8s)]
      (js/Float32Array. (.-buffer copy) 0 (quot (.-byteLength copy) 4)))))

(c/extend-packable 7 js/Float64Array
  (pack   [a] (js/Uint8Array. (.-buffer a) (.-byteOffset a) (.-byteLength a)))
  (unpack [^js u8s]
    (let [^js copy (js/Uint8Array. u8s)]
      (js/Float64Array. (.-buffer copy) 0 (quot (.-byteLength copy) 8)))))

(c/extend-packable -1 js/Date
  (pack [d]
    (let [millis   (.getTime d)
          secs     (js/Math.floor (/ millis 1000))
          nanos    (* (- millis (* secs 1000)) 1000000)
          ^js ab   (js/ArrayBuffer. 12)
          ^js view (js/DataView. ab)
          long-s   (goog.math.Long.fromNumber secs)]
      (.setUint32 view 0 nanos)
      (.setInt32  view 4 (.getHighBits long-s))
      (.setInt32  view 8 (.getLowBits  long-s))
      (js/Uint8Array. ab)))

  (unpack [u8s]
    (let [^js u8s                u8s
          ab       (.-buffer     u8s)
          off      (.-byteOffset u8s)
          len      (.-byteLength u8s)
          ^js view (js/DataView. ab off len)
          nanos    (.getUint32 view 0)
          hi       (.getInt32  view 4)
          lo       (.getInt32  view 8)
          long-s   (goog.math.Long. lo hi)
          secs     (.toNumber long-s)
          millis   (+ (* secs 1000) (/ nanos 1000000))]
      (js/Date. millis))))

(c/extend-packable 8 nil nil ; Cached key
  (unpack [u8s]
    (CachedKey. (get @c/*key-cache_* (aget ^js u8s 0)))))

(c/extend-packable 9 UUID
  (pack   [u]             (.encode text-encoder (str u)))
  (unpack [^js u8s] (uuid (.decode text-decoder ^js u8s))))

;;;;

(defn unpack [in] (unpack-1 (in-stream* in)))
(defn pack
  ([clj    ] (let [out (out-stream  1024)] (pack-bytes clj out) @out))
  ([clj out] (let [out (out-stream* out)]  (pack-bytes clj out)  out)))
