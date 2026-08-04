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
  [kw-idxs str-idxs auto-kw-idxs auto-seen-kws])

(deftype UnpackingKeyCache
  [key-vals auto-kw-state])

(def ^:no-doc ^:dynamic *key-cache_*
  "`PackingKeyCache` or `UnpackingKeyCache` for current operation."
  nil)

(def ^:private seen-kw #?(:clj (Object.) :cljs (js-obj)))

(defn ^:no-doc new-pack-key-cache [auto-cache-keywords?]
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
         :cljs (js/Map.)))))

(defn ^:no-doc new-unpack-key-cache []
  (UnpackingKeyCache.
    #?(:clj  (java.util.ArrayList.)
       :cljs (array))
    #?(:clj  (object-array 2)
       :cljs (array nil nil))))

#?(:clj
   (defmacro ^:no-doc with-pack-key-cache [auto-cache-keywords? & body]
     `(binding [*key-cache_* (new-pack-key-cache ~auto-cache-keywords?)]
        ~@body)))

#?(:clj
   (defmacro ^:no-doc with-unpack-key-cache [& body]
     `(binding [*key-cache_* (new-unpack-key-cache)]
        ~@body)))

;; Wide cache sizes must not exceed the u16 reference capacity
(def ^:private ^:const max-key-cache-size       256)
(def ^:private ^:const max-auto-kw-cache-size 65536)
(def ^:private ^:const max-auto-kw-seen-size  65536)
(def ^:no-doc  ^:const auto-kw-ref-id          10)

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
