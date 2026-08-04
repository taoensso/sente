(ns taoensso.msgpack.common
  (:require
   [taoensso.truss :as truss])
  #?(:cljs (:require-macros [taoensso.msgpack.common])))

;;;; Extensions

(defprotocol Packable    (pack-bytes [clj out]))
(defrecord   PackableExt [byte-id ba-content])

(defmulti  unpack-ext      (fn [byte-id ba-content]               byte-id))
(defmethod unpack-ext :default [byte-id ba-content] (PackableExt. byte-id ba-content))

#?(:clj
   (defmacro extend-packable [byte-id class & [pack-clause unpack-clause]]
     (let [pack-clause
           (when-let [[_ [arg] & body] pack-clause]
             `(extend-protocol Packable ~class
                (pack-bytes [~arg out#] (pack-bytes (PackableExt. ~byte-id (do ~@body)) out#))))

           unpack-clause
           (when-let [[_ [arg] & body] unpack-clause]
             `(defmethod unpack-ext ~byte-id [~'byte-id ~arg] ~@body))]

       `(do ~pack-clause ~unpack-clause))))

;;;; Map key caching

(deftype CachedKey [val])

#?(:clj
   (deftype SharedDict
     [^objects entries
      ^java.util.IdentityHashMap kw-idxs
      ^java.util.HashMap         str-idxs
      ^long max-str-len
      ^ints prefix-hashes]
     clojure.lang.IDeref (deref [_] (vec entries)))

   :cljs
   (deftype SharedDict [entries kw-idxs str-idxs max-str-len prefix-hashes]
     IDeref (-deref [_] (vec entries))))

(deftype PackingKeyCache
  [kw-idxs str-idxs auto-kw-idxs auto-seen-kws shared-dict shared-dict-active?_])

(deftype UnpackingKeyCache
  [key-vals auto-kw-state shared-dict shared-dict-count_])

(def ^:no-doc ^:dynamic *key-cache_*
  "`PackingKeyCache` or `UnpackingKeyCache` for current operation."
  nil)

(def ^:private seen-kw #?(:clj (Object.) :cljs (js-obj)))

(defn- as-shared-dict [x]
  (when x
    (if (instance? SharedDict x)
      x
      (truss/ex-info! "Expected `:shared-dict` value built with `msgpack/shared-dict`"
        {:value x, :type (type x)}))))

(defn ^:no-doc new-pack-key-cache [auto-cache-keywords? shared-dict]
  ;; JVM keywords are interned; Cljs keyword FQNs are strings.
  (PackingKeyCache.
    #?(:clj  (java.util.IdentityHashMap.)
       :cljs (js/Map.))
    #?(:clj  (java.util.HashMap.)
       :cljs (js/Map.))
    (when auto-cache-keywords?
      #?(:clj  (java.util.IdentityHashMap.)
         :cljs (js/Map.)))
    (when auto-cache-keywords?
      #?(:clj  (java.util.IdentityHashMap.)
         :cljs (js/Map.)))
    (as-shared-dict shared-dict)
    (volatile! false)))

(defn ^:no-doc new-unpack-key-cache [shared-dict]
  (UnpackingKeyCache.
    #?(:clj  (java.util.ArrayList.)
       :cljs (array))
    #?(:clj  (object-array 2)
       :cljs (array nil nil))
    (as-shared-dict shared-dict)
    (volatile! nil)))

#?(:clj
   (defmacro ^:no-doc with-pack-key-cache [auto-cache-keywords? shared-dict & body]
     `(binding [*key-cache_* (new-pack-key-cache ~auto-cache-keywords? ~shared-dict)]
        ~@body)))

#?(:clj
   (defmacro ^:no-doc with-unpack-key-cache [shared-dict & body]
     `(binding [*key-cache_* (new-unpack-key-cache ~shared-dict)]
        ~@body)))

;; Wide cache sizes must not exceed the u16 reference capacity
(def ^:private ^:const max-key-cache-size       256)
(def ^:private ^:const max-auto-kw-cache-size 65536)
(def ^:private ^:const max-auto-kw-seen-size  65536)
(def ^:no-doc  ^:const auto-kw-ref-id          10)
(def ^:no-doc  ^:const shared-dict-ref-id       11)

;;;; Shared dicts

(def ^:private ^:const max-shared-dict-size 65536)
(def ^:private ^:const fnv32-prime 16777619)
(def ^:private fnv32-offset #?(:clj (unchecked-int 2166136261) :cljs (bit-or 2166136261 0)))

(defn- fnv32-b [h b]
  #?(:clj  (unchecked-multiply-int (bit-xor (int h) (int b)) fnv32-prime)
     :cljs (js/Math.imul        (bit-xor      h       b)  fnv32-prime)))

(defn- fnv32-ba [h ba]
  (let [len #?(:clj (alength ^bytes ba) :cljs (.-byteLength ^js ba))]
    (loop [i 0, h h]
      (if (== i len)
        h
        (recur (inc i)
          (fnv32-b h
            (bit-and #?(:clj (aget ^bytes ba i) :cljs (aget ba i)) 0xff)))))))

(defn- fnv32-u32 [h n]
  (-> h
    (fnv32-b (bit-and (unsigned-bit-shift-right n 24) 0xff))
    (fnv32-b (bit-and (unsigned-bit-shift-right n 16) 0xff))
    (fnv32-b (bit-and (unsigned-bit-shift-right n  8) 0xff))
    (fnv32-b (bit-and                           n      0xff))))

#?(:cljs (def ^:private text-encoder (js/TextEncoder.)))

(defn- utf8-bytes [s]
  #?(:clj  (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.encode text-encoder s)))

(defn- utf8-encodable? [s]
  ;; Avoid divergent replacement bytes from JVM UTF-8 and JS TextEncoder.
  #?(:clj
     (.canEncode (.newEncoder java.nio.charset.StandardCharsets/UTF_8) ^String s)

     :cljs
     (let [n (.-length s)]
       (loop [i 0]
         (if (== i n)
           true
           (let [c (.charCodeAt s i)]
             (cond
               (<= 0xD800 c 0xDBFF)
               (and (< (inc i) n)
                    (<= 0xDC00 (.charCodeAt s (inc i)) 0xDFFF)
                    (recur (+ i 2)))

               (<= 0xDC00 c 0xDFFF) false
               :else (recur (inc i)))))))))

(defn- fnv32-part [h s]
  (let [ba (utf8-bytes s)]
    (fnv32-ba (fnv32-u32 h #?(:clj (alength ^bytes ba) :cljs (.-byteLength ^js ba))) ba)))

(defn- entry-hash [h x]
  ;; Fingerprint precision deliberately matches the kw wire format's (FQN):
  ;; kws that print identically (e.g. (keyword "a/b" "c"), (keyword "a" "b/c"))
  ;; conflate here exactly as they do on the wire
  (fnv32-part (fnv32-b h (if (keyword? x) 1 2)) (str x)))

(defn- kw-dict-key [kw]
  #?(:clj kw
     :cljs
     (let [ns (namespace kw)]
       (if ns
         (str (.-length ns) ":" ns (name kw))
         (str "-:" (name kw))))))

(defn ^:no-doc shared-dict
  "Returns an immutable, precompiled shared dictionary for ordered keywords
  and/or strings."
  [entries]
  (let [n (when (sequential? entries) (bounded-count (inc max-shared-dict-size) entries))]
    (when-not (and n (<= 1 n max-shared-dict-size))
      (truss/ex-info! "`shared-dict` expects a non-empty ordered sequence of <= 65536 keywords/strings"
        {:type (type entries), :count n}))

    (let [n     n
          earr  #?(:clj (object-array n) :cljs (array))
          kwm   #?(:clj (java.util.IdentityHashMap. (int n)) :cljs (js/Map.))
          strm  #?(:clj (java.util.HashMap. (int n)) :cljs (js/Map.))
          maxsl (volatile! -1)
          ph    #?(:clj (int-array (inc n)) :cljs (array fnv32-offset))]
      #?(:clj (aset ^ints ph 0 (int fnv32-offset)))
      (reduce
        (fn [idx x]
          (when-not (or (keyword? x) (and (string? x) (not= x "")))
            (truss/ex-info! "`shared-dict` entries must all be keywords or non-empty strings"
              {:idx idx, :value x, :type (type x)}))
          (when-not (utf8-encodable? (str x))
            (truss/ex-info! "`shared-dict` entries must be UTF-8 encodable"
              {:idx idx, :value x}))

          (let [kw?       (keyword? x)
                cache-key (if kw? (kw-dict-key x) x)
                m         (if kw? kwm strm)]
            (when #?(:clj  (.containsKey ^java.util.Map m cache-key)
                     :cljs (.has ^js m cache-key))
              (truss/ex-info! "`shared-dict` entries must be distinct"
                {:idx idx, :duplicate x}))
            #?(:clj
               (do (aset ^objects earr idx x)
                   (.put ^java.util.Map m cache-key idx)
                   (aset ^ints ph (inc idx) (int (entry-hash (aget ^ints ph idx) x))))

               :cljs
               (do (.push earr x)
                   (.set ^js m cache-key idx)
                   (.push ph (entry-hash (aget ph idx) x))))
            (when-not kw?
              (vswap! maxsl max #?(:clj (.length ^String x) :cljs (.-length x)))))
          (inc idx))
        0 entries)
      (SharedDict. earr kwm strm @maxsl ph))))

(defn ^:no-doc shared-dict-count [^SharedDict dict]
  (if dict
    #?(:clj  (alength ^objects (.-entries dict))
       :cljs (alength          (.-entries dict)))
    0))

(defn ^:no-doc shared-dict-pack-str-ref [^PackingKeyCache cache s]
  (when-let [^SharedDict dict (when cache (.-shared-dict cache))]
    (when (<= #?(:clj (.length ^String s) :cljs (.-length s))
              (.-max-str-len dict))
      #?(:clj  (.get ^java.util.HashMap (.-str-idxs dict) s)
         :cljs (.get ^js (.-str-idxs dict) s)))))

(defn ^:no-doc shared-dict-pack-kw-ref [^PackingKeyCache cache kw]
  (when-let [^SharedDict dict (when cache (.-shared-dict cache))]
    #?(:clj  (.get ^java.util.IdentityHashMap (.-kw-idxs dict) kw)
       :cljs (.get ^js (.-kw-idxs dict) (kw-dict-key kw)))))

(defn ^:no-doc shared-dict-pack-count [^PackingKeyCache cache]
  (shared-dict-count (when cache (.-shared-dict cache))))

(defn ^:no-doc shared-dict-pack-fingerprint [^PackingKeyCache cache]
  (let [^SharedDict dict (.-shared-dict cache)
        n (shared-dict-count dict)
        ph (.-prefix-hashes dict)]
    #?(:clj  (aget ^ints  ph n)
       :cljs (aget        ph n))))

(defn ^:no-doc shared-dict-pack-activate! [^PackingKeyCache cache]
  (let [active?_ (.-shared-dict-active?_ cache)]
    (when-not @active?_
      (vreset! active?_ true)
      true)))

(defn ^:no-doc shared-dict-unpack-count [^UnpackingKeyCache cache]
  (when cache @(.-shared-dict-count_ cache)))

(defn ^:no-doc shared-dict-unpack-activate!
  [^UnpackingKeyCache cache wire-count wire-fingerprint]
  (let [^SharedDict dict (when cache (.-shared-dict cache))]
    (when-not dict
      (truss/ex-info! "Data was packed with a `:shared-dict`, so unpacking needs the same dict"
        {:packed {:dict-count wire-count}}))
    (when (shared-dict-unpack-count cache)
      (truss/ex-info! "Unexpected activating `:shared-dict` reference"
        {:packed {:dict-count wire-count}}))
    (let [n  (shared-dict-count dict)
          ph (.-prefix-hashes dict)
          expected #?(:clj  (when (<= 1 wire-count n) (aget ^ints  ph wire-count))
                      :cljs (when (<= 1 wire-count n) (aget        ph wire-count)))
          matches? (and (some? expected) (== expected wire-fingerprint))]
      (when-not matches?
        (truss/ex-info! "`:shared-dict` mismatch: never remove or reorder entries"
          {:packed {:dict-count wire-count}, :given {:dict-count n}}))
      (vreset! (.-shared-dict-count_ cache) wire-count)
      nil)))

(defn ^:no-doc shared-dict-unpack-get [^UnpackingKeyCache cache idx]
  (let [^SharedDict dict (.-shared-dict cache)
        entries (.-entries dict)]
    #?(:clj  (aget ^objects entries idx)
       :cljs (aget          entries idx))))

(defn ^:no-doc key-cache-pack!
  "Called on map keys when packing.
  Maintains key cache, returns pre-existing cache-idx or nil."
  [^PackingKeyCache cache clj-val]
  (when (or (keyword? clj-val) (string? clj-val))
    #?(:clj
       (let [^java.util.Map m
             (if (keyword? clj-val)
               (.-kw-idxs  cache)
               (.-str-idxs cache))

             idx (.get m clj-val)]
         (if (some? idx)
           idx
           (let [n (+ (.size ^java.util.Map (.-kw-idxs  cache))
                      (.size ^java.util.Map (.-str-idxs cache)))]
             (when (< n max-key-cache-size)
               (.put m clj-val n))
             nil)))

       :cljs
       (let [kw?       (keyword? clj-val)
             ^js m     (if kw? (.-kw-idxs cache) (.-str-idxs cache))
             cache-key (if kw? (.-fqn clj-val) clj-val)
             idx       (.get m cache-key)]
         (if (some? idx)
           idx
           (let [n (+ (.-size ^js (.-kw-idxs  cache))
                      (.-size ^js (.-str-idxs cache)))]
             (when (< n max-key-cache-size)
               (.set m cache-key n))
             nil))))))

(defn ^:no-doc key-cache-unpack!
  "Called on map keys when unpacking.
  Maintains key cache, returns nil."
  [^UnpackingKeyCache cache clj-val]
  (when (or (keyword? clj-val) (string? clj-val))
    #?(:clj
       (let [^java.util.ArrayList vals (.-key-vals cache)]
         (when (< (.size vals) max-key-cache-size)
           (.add vals clj-val)))

       :cljs
       (let [vals (.-key-vals cache)]
         (when (< (alength vals) max-key-cache-size)
           (.push vals clj-val))))
    nil))

(defn ^:no-doc key-cache-unpack-count [^UnpackingKeyCache cache]
  (let [vals (.-key-vals cache)]
    #?(:clj  (.size ^java.util.ArrayList vals)
       :cljs (alength vals))))

(defn ^:no-doc key-cache-unpack-get [^UnpackingKeyCache cache idx]
  (let [vals (.-key-vals cache)]
    #?(:clj  (.get ^java.util.ArrayList vals idx)
       :cljs (aget vals idx))))

;;;; Keyword caching

(defn ^:no-doc auto-kw-cache-packing? [^PackingKeyCache cache]
  (boolean (when cache (.-auto-kw-idxs cache))))

(defn ^:no-doc auto-kw-cache-pack-ref [^PackingKeyCache cache kw]
  (when-let [m (when cache (.-auto-kw-idxs cache))]
    #?(:clj  (.get ^java.util.IdentityHashMap m kw)
       :cljs (.get ^js m (.-fqn kw)))))

(defn ^:no-doc auto-kw-cache-pack-observe! [^PackingKeyCache cache kw byte-len]
  (when (and (> byte-len 2) cache)
    (when-let [idxs (.-auto-kw-idxs cache)]
      (let [seen      (.-auto-seen-kws cache)
            cache-key #?(:clj kw :cljs (.-fqn kw))]
        (cond
          #?(:clj  (.containsKey ^java.util.IdentityHashMap seen cache-key)
             :cljs (.has ^js seen cache-key))
          (let [n #?(:clj  (.size ^java.util.IdentityHashMap idxs)
                     :cljs (.-size ^js idxs))]
            (when (< n max-auto-kw-cache-size)
              #?(:clj  (.put ^java.util.IdentityHashMap idxs cache-key n)
                 :cljs (.set ^js idxs cache-key n))))

          :else
          (when (< #?(:clj  (.size ^java.util.IdentityHashMap seen)
                      :cljs (.-size ^js seen))
                   max-auto-kw-seen-size)
            #?(:clj  (.put ^java.util.IdentityHashMap seen cache-key cache-key)
               :cljs (.set ^js seen cache-key cache-key)))))))
  nil)

(defn ^:no-doc auto-kw-cache-unpack-observe!
  [^UnpackingKeyCache cache kw byte-len]
  (when (and cache (> byte-len 2))
    (let [auto-state (.-auto-kw-state cache)
          m (or #?(:clj  (aget ^objects auto-state 0)
                    :cljs (aget          auto-state 0))
              (let [m    #?(:clj  (java.util.IdentityHashMap.) :cljs (js/Map.))
                    vals #?(:clj  (java.util.ArrayList.)       :cljs (array))]
                #?(:clj  (aset ^objects auto-state 0 m)
                   :cljs (aset          auto-state 0 m))
                #?(:clj  (aset ^objects auto-state 1 vals)
                   :cljs (aset          auto-state 1 vals))
                m))
          vals      #?(:clj  (aget ^objects auto-state 1)
                       :cljs (aget          auto-state 1))
          cache-key #?(:clj kw :cljs (.-fqn kw))
          state     #?(:clj  (.get ^java.util.IdentityHashMap m cache-key)
                       :cljs (.get ^js m cache-key))]
      (cond
        (identical? state seen-kw)
        (let [n #?(:clj  (.size ^java.util.ArrayList vals)
                   :cljs (alength vals))]
          (when (< n max-auto-kw-cache-size)
            #?(:clj
               (do (.put ^java.util.IdentityHashMap m cache-key n)
                   (.add ^java.util.ArrayList vals kw))
               :cljs
               (do (.set ^js m cache-key n)
                   (.push vals kw)))))

        (nil? state)
        (when (< #?(:clj  (.size ^java.util.IdentityHashMap m)
                    :cljs (.-size ^js m))
                 max-auto-kw-seen-size)
          #?(:clj  (.put ^java.util.IdentityHashMap m cache-key seen-kw)
             :cljs (.set ^js m cache-key seen-kw)))))
    nil))

(defn ^:no-doc auto-kw-cache-unpack-count [^UnpackingKeyCache cache]
  (if-let [vals (when cache
                  #?(:clj  (aget ^objects (.-auto-kw-state cache) 1)
                     :cljs (aget          (.-auto-kw-state cache) 1)))]
    #?(:clj  (.size ^java.util.ArrayList vals)
       :cljs (alength vals))
    0))

(defn ^:no-doc auto-kw-cache-unpack-get [^UnpackingKeyCache cache idx]
  (let [vals #?(:clj  (aget ^objects (.-auto-kw-state cache) 1)
                :cljs (aget          (.-auto-kw-state cache) 1))]
    #?(:clj  (.get ^java.util.ArrayList vals idx)
       :cljs (aget vals idx))))
