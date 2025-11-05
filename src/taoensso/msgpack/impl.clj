(ns taoensso.msgpack.impl
  (:require
   [taoensso.truss :as truss]
   [taoensso.msgpack.common :as c :refer [Packable pack-bytes]])

  (:import
   [taoensso.msgpack.common PackableExt CachedKey]
   [java.nio ByteBuffer ByteOrder]
   [java.nio.charset StandardCharsets]
   [java.io
    ByteArrayInputStream ByteArrayOutputStream DataInput DataOutput
    DataInputStream DataOutputStream InputStream OutputStream]))

;;;; Utils

(defmacro with-out [[out size] & body]
  `(let [baos# (ByteArrayOutputStream. ~size)
         ~out  (DataOutputStream. baos#)]
     ~@body
     (.toByteArray baos#)))

(defmacro with-in [[in ba] & body]
  `(let [bais# (ByteArrayInputStream. ~(with-meta ba {:tag 'bytes}))
         ~in   (DataInputStream. bais#)]
     ~@body))

;;;; Packing

(defn- pack-ba  [^bytes ba ^DataOutput out]
  (let [len (count ba)]
    (cond
      (<= len 0xff)       (do (.writeByte out 0xc4) (.writeByte  out len) (.write out ba))
      (<= len 0xffff)     (do (.writeByte out 0xc5) (.writeShort out len) (.write out ba))
      (<= len 0xffffffff) (do (.writeByte out 0xc6) (.writeInt   out len) (.write out ba)))))

(defn- pack-str [^String s ^DataOutput out]
  (let [ba  (.getBytes s StandardCharsets/UTF_8)
        len (alength ba)]
    (cond
      (<= len 0x1f)       (do (.writeByte out     (bit-or 2r10100000 len)) (.write out ba))
      (<= len 0xff)       (do (.writeByte out 0xd9) (.writeByte  out len)  (.write out ba))
      (<= len 0xffff)     (do (.writeByte out 0xda) (.writeShort out len)  (.write out ba))
      (<= len 0xffffffff) (do (.writeByte out 0xdb) (.writeInt   out len)  (.write out ba)))))

(defn- pack-int [^long n ^DataOutput out]
  (if (neg? n)
    (cond
      (>= n -32)                                   (.writeByte  out n)  ; -fixnum
      (>= n -0x80)       (do (.writeByte out 0xd0) (.writeByte  out n)) ; int8
      (>= n -0x8000)     (do (.writeByte out 0xd1) (.writeShort out n)) ; int16
      (>= n -0x80000000) (do (.writeByte out 0xd2) (.writeInt   out n)) ; int32
      :else              (do (.writeByte out 0xd3) (.writeLong  out n)) ; int64
      )

    (cond
      (<= n 127)                                          (.writeByte  out n)                   ; +fixnum
      (<= n 0xff)               (do (.writeByte out 0xcc) (.writeByte  out n))                  ; uint8
      (<= n 0xffff)             (do (.writeByte out 0xcd) (.writeShort out n))                  ; uint16
      (<= n 0xffffffff)         (do (.writeByte out 0xce) (.writeInt   out (unchecked-int  n))) ; uint32
      (<= n 0x7fffffffffffffff) (do (.writeByte out 0xd3) (.writeLong  out n))                  ;  int64 if possible
      :else                     (do (.writeByte out 0xcf) (.writeLong  out (unchecked-long n))) ; uint64
      )))

(defn- pack-coll [c ^DataOutput out] (reduce (fn [_ el] (pack-bytes el out)) nil c))
(defn- pack-seq  [s ^DataOutput out]
  (let [len (count s)]
    (cond
      (<= len 0xf)        (do (.writeByte out     (bit-or 2r10010000 len)) (pack-coll s out))
      (<= len 0xffff)     (do (.writeByte out 0xdc) (.writeShort out len)  (pack-coll s out))
      (<= len 0xffffffff) (do (.writeByte out 0xdd) (.writeInt   out len)  (pack-coll s out)))))

#_(defn- pack-kvs [m ^DataOutput out] (reduce-kv (fn [_ k v] (pack-bytes k  out) (pack-bytes v out)) nil m))
(defn-   pack-kvs [m ^DataOutput out]
  (let [key-cache_ c/*key-cache_*]
    (reduce-kv
      (fn [_ k v]
        (if-let [cache-idx (when key-cache_ (c/key-cache-pack! key-cache_ k))]
          #_(pack-bytes (PackableExt. 8 (with-out [out 1] (.writeByte out cache-idx))) out)
          (do
            (.writeByte out 0xd4)
            (.writeByte out 8)
            (.writeByte out cache-idx))
          (pack-bytes k out))
        (pack-bytes   v out))
      nil m)))

(defn- pack-map [m ^DataOutput out]
  (let [len (count m)]
    (cond
      (<= len 0xf)        (do (.writeByte out     (bit-or 2r10000000 len)) (pack-kvs m out))
      (<= len 0xffff)     (do (.writeByte out 0xde) (.writeShort out len)  (pack-kvs m out))
      (<= len 0xffffffff) (do (.writeByte out 0xdf) (.writeInt   out len)  (pack-kvs m out)))))

(extend-protocol Packable
  nil                         (pack-bytes [_ ^DataOutput out]       (.writeByte out 0xc0))
  java.lang.Boolean           (pack-bytes [b ^DataOutput out] (if b (.writeByte out 0xc3) (.writeByte out 0xc2)))
  java.lang.String            (pack-bytes [s ^DataOutput out] (pack-str s out))
  ;;
  java.lang.Byte              (pack-bytes [n ^DataOutput out] (pack-int n out))
  java.lang.Short             (pack-bytes [n ^DataOutput out] (pack-int n out))
  java.lang.Integer           (pack-bytes [n ^DataOutput out] (pack-int n out))
  java.lang.Long              (pack-bytes [n ^DataOutput out] (pack-int n out))
  java.math.BigInteger        (pack-bytes [n ^DataOutput out] (pack-int (.longValueExact                n)  out)) ; i64 range, or throw
  clojure.lang.BigInt         (pack-bytes [n ^DataOutput out] (pack-int (.longValueExact (.toBigInteger n)) out)) ; ''
  ;;
  java.lang.Float             (pack-bytes [f ^DataOutput out] (do (.writeByte out 0xca) (.writeFloat  out f)))
  java.lang.Double            (pack-bytes [d ^DataOutput out] (do (.writeByte out 0xcb) (.writeDouble out d)))
  java.math.BigDecimal        (pack-bytes [d ^DataOutput out] (pack-bytes (.doubleValue d) out))
  clojure.lang.Sequential     (pack-bytes [s ^DataOutput out] (pack-seq s out))
  clojure.lang.IPersistentMap (pack-bytes [m ^DataOutput out] (pack-map m out))
  ;;
  PackableExt
  (pack-bytes [x ^DataOutput out]
    (let [byte-id   (.-byte-id    x)
          ^bytes ba (.-ba-content x)
          len       (alength ba)]

      (case len
        1  (.writeByte out 0xd4)
        2  (.writeByte out 0xd5)
        4  (.writeByte out 0xd6)
        8  (.writeByte out 0xd7)
        16 (.writeByte out 0xd8)
        (cond
          (<= len 0xff)       (do (.writeByte out 0xc7) (.writeByte  out len))
          (<= len 0xffff)     (do (.writeByte out 0xc8) (.writeShort out len))
          (<= len 0xffffffff) (do (.writeByte out 0xc9) (.writeInt   out len))))

      (.writeByte out byte-id)
      (.write     out ba)))

  Object
  (pack-bytes [x ^DataOutput out]
    (pack-bytes
      {:msgpack/unpackable
       {:type (str (type x))
        :preview
        (try
          (let [out (pr-str x)] (subs out 0 (min 32 (count out))))
          (catch Throwable _ :unprintable))}}
      out)))

;; Separate for CLJ-1381
(extend-protocol Packable (class (into-array Byte [])) (pack-bytes [a  ^DataOutput out] (pack-bytes (byte-array a) out)))
(extend-protocol Packable (class (byte-array 0))       (pack-bytes [ba ^DataOutput out] (pack-ba    ba             out)))

;;;; Unpacking

(defn- read-u8  [^DataInput in] (.readUnsignedByte            in))
(defn- read-u16 [^DataInput in] (.readUnsignedShort           in))
(defn- read-u32 [^DataInput in] (bit-and 0xffffffff (.readInt in)))
(defn- read-u64 [^DataInput in] (let [n (.readLong in)] (if (neg? n) (.and (BigInteger/valueOf n) (biginteger 0xffffffffffffffffN)) n)))

(defn- read-bytes ^bytes [n ^DataInput in] (let [ba (byte-array n)] (.readFully in ba) ba))
(defn- read-str          [n ^DataInput in] (let [ba (read-bytes n in)] (String. ba StandardCharsets/UTF_8)))

(declare unpack-1)
(defn-   unpack-n   [init n ^DataInput in] (persistent! (reduce (fn [acc _] (conj!  acc (unpack-1 in)))               (transient init) (range n))))
#_(defn- unpack-map [     n ^DataInput in] (persistent! (reduce (fn [acc _] (assoc! acc (unpack-1 in) (unpack-1 in))) (transient {})   (range n))))
(defn-   unpack-map [     n ^DataInput in]
  (let [key-cache_ c/*key-cache_*]
    (persistent!
      (reduce
        (fn [acc _]
          (let [k (unpack-1 in)
                k
                (if (instance? CachedKey k)
                  (.-val      ^CachedKey k)
                  (do (c/key-cache-unpack! key-cache_ k) k))]
            (assoc! acc k (unpack-1 in))))
        (transient {}) (range n)))))

(defn-   unpack-1        [^DataInput in]
  (let   [byte-id (.readUnsignedByte in)]
    (case byte-id
      0xc0 nil
      0xc2 false
      0xc3 true

      ;; Ints
      0xcc (long (read-u8    in))
      0xcd (long (read-u16   in))
      0xce (long (read-u32   in))
      0xcf       (read-u64   in)
      0xd0 (long (.readByte  in))
      0xd1 (long (.readShort in))
      0xd2 (long (.readInt   in))
      0xd3       (.readLong  in)

      ;; Floats
      0xca (double (.readFloat  in))
      0xcb         (.readDouble in)

      ;; Strings
      0xd9 (read-str (read-u8  in) in)
      0xda (read-str (read-u16 in) in)
      0xdb (read-str (read-u32 in) in)

      ;; Byte arrays
      0xc4 (read-bytes (read-u8  in) in)
      0xc5 (read-bytes (read-u16 in) in)
      0xc6 (read-bytes (read-u32 in) in)

      ;; Seqs
      0xdc (unpack-n [] (read-u16 in) in)
      0xdd (unpack-n [] (read-u32 in) in)

      ;; Maps
      0xde (unpack-map (read-u16 in) in)
      0xdf (unpack-map (read-u32 in) in)

      ;; Extensions
      0xd4                        (c/unpack-ext (.readByte in) (read-bytes 1  in))
      0xd5                        (c/unpack-ext (.readByte in) (read-bytes 2  in))
      0xd6                        (c/unpack-ext (.readByte in) (read-bytes 4  in))
      0xd7                        (c/unpack-ext (.readByte in) (read-bytes 8  in))
      0xd8                        (c/unpack-ext (.readByte in) (read-bytes 16 in))
      0xc7 (let [n (read-u8  in)] (c/unpack-ext (.readByte in) (read-bytes n  in)))
      0xc8 (let [n (read-u16 in)] (c/unpack-ext (.readByte in) (read-bytes n  in)))
      0xc9 (let [n (read-u32 in)] (c/unpack-ext (.readByte in) (read-bytes n  in)))

      ;; Fix types
      (cond
        (== (bit-and byte-id 2r10000000) 0)          (long (unchecked-byte byte-id)) ; +fixnum
        (== (bit-and byte-id 2r11100000) 2r11100000) (long (unchecked-byte byte-id)) ; -fixnum
        (== (bit-and byte-id 2r11100000) 2r10100000) (let [n      (bit-and 2r11111 byte-id)] (read-str n in)) ; String
        (== (bit-and byte-id 2r11110000) 2r10010000) (unpack-n [] (bit-and 2r1111  byte-id)              in)  ; Seq
        (== (bit-and byte-id 2r11110000) 2r10000000) (unpack-map  (bit-and 2r1111  byte-id)              in)  ; Map
        :else (truss/ex-info! "Unpack failed: unexpected `byte-id`" {:byte-id byte-id})))))

;;;; Built-in extensions

(c/extend-packable 0 clojure.lang.Keyword
  (pack   [k]  (.getBytes (.substring (str k) 1) StandardCharsets/UTF_8))
  (unpack [ba] (keyword (String. ^bytes ba       StandardCharsets/UTF_8))))

(c/extend-packable 1 clojure.lang.Symbol
  (pack   [s]  (.getBytes (str s)         StandardCharsets/UTF_8))
  (unpack [ba] (symbol (String. ^bytes ba StandardCharsets/UTF_8))))

(c/extend-packable 2 java.lang.Character
  (pack   [c]  (.getBytes (str c)          StandardCharsets/UTF_8))
  (unpack [ba] (.charAt (String. ^bytes ba StandardCharsets/UTF_8) 0)))

(c/extend-packable 3 clojure.lang.Ratio
  (pack [r]
    (with-out [out 32]
      (pack-bytes (numerator   r) out)
      (pack-bytes (denominator r) out)))

  (unpack [ba] (with-in [in ba] (/ (unpack-1 in) (unpack-1 in)))))

(c/extend-packable 4 clojure.lang.IPersistentSet
  (pack   [s]  (with-out [out 128] (pack-seq s out)))
  (unpack [ba]
    (with-in [in ba]
      (let    [byte-id (.readUnsignedByte in)]
        (case  byte-id
          0xdc (unpack-n #{} (read-u16 in)            in)
          0xdd (unpack-n #{} (read-u32 in)            in)
          (do  (unpack-n #{} (bit-and 2r1111 byte-id) in)))))))

(c/extend-packable 5 (class (int-array 0))
  (pack  [ia]
    (let [ba (byte-array (* 4 (alength ^ints ia)))
          bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
          ib (.asIntBuffer bb)]
      (.put ib ^ints ia)
      (.array bb)))

  (unpack [ba]
    (let  [bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
           ib (.asIntBuffer bb)
           ia (int-array (.remaining ib))]
      (.get ib ia)
      ia)))

(c/extend-packable 6 (class (float-array 0))
  (pack  [fa]
    (let [ba (byte-array (* 4 (alength ^floats fa)))
          bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
          fb (.asFloatBuffer bb)]
      (.put fb ^floats fa)
      (.array bb)))

  (unpack [ba]
    (let  [bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
           fb (.asFloatBuffer bb)
           fa (float-array (.remaining fb))]
      (.get fb fa)
      fa)))

(c/extend-packable 7 (class (double-array 0))
  (pack  [da]
    (let [ba (byte-array (* 8 (alength ^doubles da)))
          bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
          db (.asDoubleBuffer bb)]
      (.put db ^doubles da)
      (.array bb)))

  (unpack [ba]
    (let  [bb (doto (ByteBuffer/wrap ba) (.order ByteOrder/BIG_ENDIAN))
           db (.asDoubleBuffer bb)
           da (double-array (.remaining db))]
      (.get db da)
      da)))

(defn- instant->ba [^java.time.Instant i]
  (let [bb (ByteBuffer/allocate 12)]
    (.putInt  bb (.getNano        i))
    (.putLong bb (.getEpochSecond i))
    (.array   bb)))

(c/extend-packable -1 java.util.Date (pack [d] (instant->ba (.toInstant d))))
(c/extend-packable -1 java.time.Instant
  (pack   [i] (instant->ba i))
  (unpack [ba]
    (let  [bb (ByteBuffer/wrap ba)
           nanos (.getInt  bb)
           secs  (.getLong bb)]
      (java.time.Instant/ofEpochSecond secs nanos))))

(c/extend-packable 8 nil nil ; Cached key
  (unpack [ba]
    (CachedKey. (get @c/*key-cache_* (bit-and 0xff (aget ^bytes ba 0))))))

(c/extend-packable 9 java.util.UUID
  (pack   [u]  (.getBytes (str u)                            StandardCharsets/UTF_8))
  (unpack [ba] (java.util.UUID/fromString (String. ^bytes ba StandardCharsets/UTF_8))))

#_ ; Unfortunately no simple equivalent for Cljs
(c/extend-packable 9 java.util.UUID
  (pack [u]
    (with-out [out 64]
      (.writeLong out (.getMostSignificantBits  u))
      (.writeLong out (.getLeastSignificantBits u))))

  (unpack [ba] (with-in [in ba] (java.util.UUID. (.readLong in) (.readLong in)))))

(comment (unpack (pack (java.util.UUID/randomUUID))))

;;;;

(defn pack
  (^bytes      [clj                ] (with-out [out 1024] (pack-bytes clj out)))
  (^DataOutput [clj ^DataOutput out]                      (pack-bytes clj out) out))

(defn unpack [in]
  (let [^DataInput in
        (cond
          (bytes?                in) (DataInputStream. (ByteArrayInputStream. in))
          (instance? DataInput   in)                                          in
          :else
          (truss/ex-info! "Unexpected unpack `input` type"
            {:given {:value in, :type (type in)}
             :expected '#{bytes DataInput}}))]
    (unpack-1 in)))
