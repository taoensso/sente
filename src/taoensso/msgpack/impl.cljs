(ns taoensso.msgpack.impl
  (:require
   [cljs.reader]
   [goog.crypt]
   [goog.math.Long]
   [taoensso.msgpack.interfaces :as i
    :refer [Packable PackableExt pack-bytes]]))

;;;; Streams

(defprotocol IStream
  (stream->ba       [_])
  (inc-offset!      [_ n])
  (ensure-capacity! [_ n]))

(defprotocol IInputStream
  (read-1   [_ n])
  (read-ba  [_ n])
  (read-str [_ n])
  (read-u8  [_])
  (read-i8  [_])
  (read-u16 [_])
  (read-i16 [_])
  (read-u32 [_])
  (read-i32 [_])
  (read-u64 [_])
  (read-i64 [_])
  (read-f32 [_])
  (read-f64 [_]))

(defprotocol IOutputStream
  (write-1   [_ buffer])
  (write-u8  [_ u8])
  (write-i8  [_ i8])
  (write-u16 [_ u16])
  (write-i16 [_ i16])
  (write-u32 [_ u32])
  (write-i32 [_ i32])
  (write-u64 [_ u64])
  (write-i64 [_ i64])
  (write-f64 [_ f64]))

(deftype InputStream [^:unsynchronized-mutable offset out]
  IStream
  (stream->ba       [_  ] (js/Uint8Array. (.-buffer out)))
  (inc-offset!      [_ n] (set! offset (+ offset n)))
  (ensure-capacity! [_ _] nil)

  IInputStream
  (read-1 [this n]
    (let [old-offset offset]
      (inc-offset! this n)
      (.slice (.-buffer out) old-offset offset)))

  (read-ba    [this n] (js/Uint8Array.                    (read-1  this n)))
  (read-str   [this n] (.utf8ByteArrayToString goog.crypt (read-ba this n)))

  (read-u8    [this] (let [u8  (.getUint8   out offset)]       (inc-offset! this 1) u8))
  (read-i8    [this] (let [i8  (.getInt8    out offset)]       (inc-offset! this 1) i8))
  (read-u16   [this] (let [u16 (.getUint16  out offset)]       (inc-offset! this 2) u16))
  (read-i16   [this] (let [i16 (.getInt16   out offset false)] (inc-offset! this 2) i16))
  (read-u32   [this] (let [u32 (.getUint32  out offset false)] (inc-offset! this 4) u32))
  (read-i32   [this] (let [i32 (.getInt32   out offset false)] (inc-offset! this 4) i32))

  (read-f32   [this] (let [f32 (.getFloat32 out offset false)] (inc-offset! this 4) f32))
  (read-f64   [this] (let [f64 (.getFloat64 out offset false)] (inc-offset! this 8) f64))

  (read-u64   [this]
    (let [hi (.getUint32 out    offset    false)
          lo (.getUint32 out (+ offset 4) false)]
      (inc-offset! this 8)
      (+ (* hi 0x100000000) lo)))

  (read-i64 [this]
    (let [hi (.getInt32 out    offset    false)
          lo (.getInt32 out (+ offset 4) false)]
      (inc-offset! this 8)
      (.toNumber (goog.math.Long. lo hi)))))

(deftype OutputStream
  [^:unsynchronized-mutable offset
   ^:unsynchronized-mutable out]

  IStream
  (stream->ba       [_  ] (js/Uint8Array. (.-buffer out) 0 offset))
  (inc-offset!      [_ n] (set! offset (+ offset n)))
  (ensure-capacity! [_ n]
    (let [base (+ offset n)]
      (when (> base (.-byteLength out))
        (let [old-ba (js/Uint8Array. (.-buffer out))
              new-ba (js/Uint8Array. (bit-or 0 (* 1.5 base)))]
          (set! out (js/DataView. (.-buffer new-ba)))
          (.set new-ba old-ba 0)))))

  IOutputStream
  (write-1 [this buffer]
    (ensure-capacity! this (.-byteLength buffer))
    (if (instance? js/ArrayBuffer buffer)
      (.set (js/Uint8Array. (.-buffer out)) (js/Uint8Array. buffer) offset)
      (.set (js/Uint8Array. (.-buffer out))                 buffer  offset))
    (inc-offset! this (.-byteLength buffer)))

  (write-u8  [this u8]  (ensure-capacity! this 1) (.setUint8   out offset u8  false) (inc-offset! this 1))
  (write-i8  [this i8]  (ensure-capacity! this 1) (.setInt8    out offset i8  false) (inc-offset! this 1))
  (write-u16 [this u16] (ensure-capacity! this 2) (.setUint16  out offset u16 false) (inc-offset! this 2))
  (write-i16 [this i16] (ensure-capacity! this 2) (.setInt16   out offset i16 false) (inc-offset! this 2))
  (write-u32 [this u32] (ensure-capacity! this 4) (.setUint32  out offset u32 false) (inc-offset! this 4))
  (write-i32 [this i32] (ensure-capacity! this 4) (.setInt32   out offset i32 false) (inc-offset! this 4))
  (write-f64 [this f64] (ensure-capacity! this 8) (.setFloat64 out offset f64 false) (inc-offset! this 8))

  (write-u64 [this u64] ; round if |n| > js/Number.MAX_SAFE_INTEGER
    (let [raw-hi (Math/floor (/ u64 0x100000000))
          hi     (min raw-hi 0xFFFFFFFF)
          raw-lo (Math/floor (- u64 (* hi 0x100000000)))
          lo     (min (max raw-lo 0) 0xFFFFFFFF)]
      (write-u32 this hi)
      (write-u32 this lo)))

  (write-i64 [this i64] ; round if |n| > js/Number.MAX_SAFE_INTEGER
    (let [gl (goog.math.Long.fromNumber i64)]
      (write-i32 this (.getHighBits gl))
      (write-i32 this (.getLowBits  gl)))))

(defn-  in-stream [in]  (InputStream.  0 (js/DataView. in)))
(defn- out-stream [out] (OutputStream. 0 (js/DataView. out)))

;;;; Packing

(defn- type-name [x]
  (let [ctor (type x)]
    (or
      (.-name ctor) (.-displayName ctor) (goog/typeOf x)
      (try (pr-str ctor) (catch :default _ nil))
      "<unknown>")))

(defn- pack-ba [ba out]
  (let [len (.-byteLength ba)]
    (cond
      (<= len 0xff)       (do (write-u8 out 0xc4) (write-u8  out len) (write-1 out ba))
      (<= len 0xffff)     (do (write-u8 out 0xc5) (write-u16 out len) (write-1 out ba))
      (<= len 0xffffffff) (do (write-u8 out 0xc6) (write-u32 out len) (write-1 out ba)))))

(defn- pack-str [s out]
  (let [ba  (js/Uint8Array. (.stringToUtf8ByteArray goog.crypt s))
        len (.-byteLength ba)]
    (cond
      (<= len 0x1f)       (do (write-u8 out   (bit-or 2r10100000 len)) (write-1 out ba))
      (<= len 0xff)       (do (write-u8 out 0xd9) (write-u8  out len)  (write-1 out ba))
      (<= len 0xffff)     (do (write-u8 out 0xda) (write-u16 out len)  (write-1 out ba))
      (<= len 0xffffffff) (do (write-u8 out 0xdb) (write-u32 out len)  (write-1 out ba)))))

(defn- pack-dbl [n out] (do (write-u8 out 0xcb) (write-f64 out n)))
(defn- pack-int [n out]
  (if (neg? n)
    (cond
      (>= n -32)                                         (write-i8  out n)  ; -fixnum
      (>= n -0x80)               (do (write-u8 out 0xd0) (write-i8  out n)) ; int8
      (>= n -0x8000)             (do (write-u8 out 0xd1) (write-i16 out n)) ; int16
      (>= n -0x80000000)         (do (write-u8 out 0xd2) (write-i32 out n)) ; int32
      (>= n -0x8000000000000000) (do (write-u8 out 0xd3) (write-i64 out n)) ; int64
      :else (throw (ex-info "Int too small to pack" {:type (type-name n), :value n})))

    (cond
      (<= n 127)                                        (write-u8  out n)  ; +fixnum
      (<= n 0xff)               (do (write-u8 out 0xcc) (write-u8  out n)) ; uint8
      (<= n 0xffff)             (do (write-u8 out 0xcd) (write-u16 out n)) ; uint16
      (<= n 0xffffffff)         (do (write-u8 out 0xce) (write-u32 out n)) ; uint32
      (<= n 0xffffffffffffffff) (do (write-u8 out 0xcf) (write-u64 out n)) ; uint64
      :else (throw (ex-info "Int too large to pack" {:type (type-name n), :value n})))))

(defn- pack-num  [n out] (if (integer? n) (pack-int n out) (pack-dbl n out)))
(defn- pack-coll [c out] (reduce    (fn [_ el]  (pack-bytes el out))                    nil c))
(defn- pack-kvs  [m out] (reduce-kv (fn [_ k v] (pack-bytes k  out) (pack-bytes v out)) nil m))
(defn- pack-seq  [s out]
  (let [len (count s)]
    (cond
      (<= len 0xf)        (do (write-u8 out   (bit-or 2r10010000 len)) (pack-coll s out))
      (<= len 0xffff)     (do (write-u8 out 0xdc) (write-u16 out len)  (pack-coll s out))
      (<= len 0xffffffff) (do (write-u8 out 0xdd) (write-u32 out len)  (pack-coll s out)))))

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
  js/Uint8Array      (pack-bytes [a out] (pack-ba       a  out))
  ;;
  PackableExt
  (pack-bytes [x out]
    (let [byte-id    (.-byte-id    x)
          ba-content (.-ba-content x)
          len (.-byteLength ba-content)]

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
      (write-u8 out byte-id)
      (write-1  out ba-content)))

  default
  (pack-bytes [x out]
    (if (seqable? x)
      (pack-seq x out)
      (pack-bytes
        {:msgpack/unpackable
         {:type (type-name x)
          :preview
          (try
            (let [s (pr-str x)] (subs s 0 (min 16 (count s))))
            (catch :default _ "<unprintable>"))}}
        out))))

;;;; Unpacking

(declare unpack-1)
(defn-   unpack-n   [init n in] (persistent! (reduce (fn [acc _] (conj!  acc (unpack-1 in)))               (transient init) (range n))))
(defn-   unpack-map [     n in] (persistent! (reduce (fn [acc _] (assoc! acc (unpack-1 in) (unpack-1 in))) (transient   {}) (range n))))
(defn-   unpack-1   [       in]
  (let   [byte-id (read-u8 in)]
    (case byte-id
      0xc0 nil
      0xc2 false
      0xc3 true

      ;; Ints
      0xcc (read-u8  in)
      0xcd (read-u16 in)
      0xce (read-u32 in)
      0xcf (read-u64 in)
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
      0xc4 (read-ba in (read-u8  in))
      0xc5 (read-ba in (read-u16 in))
      0xc6 (read-ba in (read-u32 in))

      ;; Seqs
      0xdc (unpack-n [] (read-u16 in) in)
      0xdd (unpack-n [] (read-u32 in) in)

      ;; Maps
      0xde (unpack-map (read-u16 in) in)
      0xdf (unpack-map (read-u32 in) in)

      ;; Extensions
      0xd4                        (i/unpack-ext (read-i8 in) (read-1 in 1))
      0xd5                        (i/unpack-ext (read-i8 in) (read-1 in 2))
      0xd6                        (i/unpack-ext (read-i8 in) (read-1 in 4))
      0xd7                        (i/unpack-ext (read-i8 in) (read-1 in 8))
      0xd8                        (i/unpack-ext (read-i8 in) (read-1 in 16))
      0xc7 (let [n (read-u8  in)] (i/unpack-ext (read-i8 in) (read-1 in n)))
      0xc8 (let [n (read-u16 in)] (i/unpack-ext (read-i8 in) (read-1 in n)))
      0xc9 (let [n (read-u32 in)] (i/unpack-ext (read-i8 in) (read-1 in n)))

      ;; Fix types
      (cond
        (== (bit-and byte-id 2r10000000) 0)             byte-id        ; +fixnum
        (== (bit-and byte-id 2r11100000) 2r11100000) (- byte-id 0x100) ; -fixnum
        (== (bit-and byte-id 2r11100000) 2r10100000) (read-str in (bit-and 2r11111 byte-id))    ; String
        (== (bit-and byte-id 2r11110000) 2r10010000) (unpack-n [] (bit-and 2r1111  byte-id) in) ; Seq
        (== (bit-and byte-id 2r11110000) 2r10000000) (unpack-map  (bit-and 2r1111  byte-id) in) ; Map
        :else (throw (ex-info "Unpack failed: unexpected `byte-id`" {:byte-id byte-id}))))))

(defn pack
  ([clj    ] (let [out (out-stream (js/ArrayBuffer. 2048))] (pack-bytes clj out) (stream->ba out)))
  ([clj out] (let [out (out-stream out)]                    (pack-bytes clj out))))

(defn unpack [packed]
  (cond
    (instance? js/Uint8Array packed) (unpack-1 (in-stream (.-buffer packed)))
    :else                            (unpack-1 (in-stream           packed))))

;;;; Built-in extensions

(i/extend-packable 0 Keyword
  (pack   [k]  (pack (.substring (str k) 1)))
  (unpack [ba] (keyword (unpack-1 (in-stream ba)))))

(i/extend-packable 1 Symbol
  (pack   [s]  (pack (str s)))
  (unpack [ba] (symbol (unpack-1 (in-stream ba)))))

(i/extend-packable 2 nil ; Char
  nil
  (unpack [ba] (unpack-1 (in-stream ba))))

(i/extend-packable 3 nil ; Ratio
  nil
  (unpack [ba] (let [[n d] (unpack-1 (in-stream ba))] (/ n d))))

(i/extend-packable 4 PersistentHashSet
  (pack   [s]  (pack (or (seq s) [])))
  (unpack [ba]
    (let [in (in-stream ba)]
      (let    [byte-id (read-u8 in)]
        (case  byte-id
          0xdc (unpack-n #{} (read-u16 in)            in)
          0xdd (unpack-n #{} (read-u32 in)            in)
          (do  (unpack-n #{} (bit-and 2r1111 byte-id) in)))))))

(i/extend-packable 5 js/Int32Array
  (pack   [a] (.-buffer a))
  (unpack [ba] (js/Int32Array. ba)))

(i/extend-packable 6 js/Float32Array
  (pack   [a]  (.-buffer a))
  (unpack [ba] (js/Float32Array. ba)))

(i/extend-packable 7 js/Float64Array
  (pack   [a]  (.-buffer a))
  (unpack [ba] (js/Float64Array. ba)))

(i/extend-packable -1 js/Date
  (pack [d]
    (let [millis (.getTime d)
          secs   (js/Math.floor (/ millis 1000))
          nanos  (* (- millis   (* secs   1000)) 1000000)
          buffer (js/ArrayBuffer. 12)
          view   (js/DataView. buffer)
          long-secs (goog.math.Long.fromNumber secs)]
      (.setUint32 view 0 nanos false)
      (.setInt32  view 4 (.getHighBits long-secs) false)
      (.setInt32  view 8 (.getLowBits  long-secs) false)
      buffer))

  (unpack [ba]
    (let [view  (js/DataView. ba)
          nanos (.getUint32 view 0 false)
          hi    (.getInt32  view 4 false)
          lo    (.getInt32  view 8 false)
          long-secs (goog.math.Long. lo hi)
          secs      (.toNumber long-secs)
          millis (+ (* secs 1000) (/ nanos 1000000))]
      (js/Date. millis))))
