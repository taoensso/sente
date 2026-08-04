(ns taoensso.msgpack.common
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

(deftype PackingKeyCache
  [kw-idxs str-idxs])

(def ^:no-doc ^:dynamic *key-cache_*
  "When packing: `PackingKeyCache`, when unpacking: indexed mutable list."
  nil)

(defn ^:no-doc new-pack-key-cache []
  ;; JVM keywords are interned; Cljs keyword FQNs are strings.
  (PackingKeyCache.
    #?(:clj  (java.util.IdentityHashMap.)
       :cljs (js/Map.))
    #?(:clj  (java.util.HashMap.)
       :cljs (js/Map.))))

(defn ^:no-doc new-unpack-key-cache []
  #?(:clj  (java.util.ArrayList.)
     :cljs (array)))

#?(:clj
   (defmacro ^:no-doc with-pack-key-cache [& body]
     `(binding [*key-cache_* (new-pack-key-cache)]
        ~@body)))

#?(:clj
   (defmacro ^:no-doc with-unpack-key-cache [& body]
     `(binding [*key-cache_* (new-unpack-key-cache)]
        ~@body)))

(def ^:private ^:const max-key-cache-size 256)

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
  [cache clj-val]
  (when (or (keyword? clj-val) (string? clj-val))
    #?(:clj
       (let [^java.util.ArrayList cache cache]
         (when (< (.size cache) max-key-cache-size)
           (.add cache clj-val)))

       :cljs
       (when (< (alength cache) max-key-cache-size)
         (.push cache clj-val)))
    nil))

(defn ^:no-doc key-cache-unpack-count [cache]
  #?(:clj  (.size ^java.util.ArrayList cache)
     :cljs (alength cache)))

(defn ^:no-doc key-cache-unpack-get [cache idx]
  #?(:clj  (.get ^java.util.ArrayList cache idx)
     :cljs (aget cache idx)))
