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
(def ^:no-doc ^:dynamic *key-cache_*
  "When  packing: (atom {clj-val cache-idx}),
  When unpacking: (atom {cache-idx clj-val})."
  nil)

#?(:clj
   (defmacro ^:no-doc with-key-cache [& body]
     `(binding [*key-cache_* (atom {})]
        ~@body)))

(defn ^:no-doc key-cache-pack!
  "Called on map keys when packing.
  Maintains key cache, returns pre-existing cache-idx or nil."
  [cache_ clj-val]
  (when (or (keyword? clj-val) (string? clj-val))
    (or
      (get @cache_ clj-val)
      (do
        (swap! cache_
          (fn [m]
            (or (get m clj-val)
              (let [n (count m)]
                (if (<= n 255)
                  (assoc m clj-val n)
                  (do    m))))))
        nil))))

(defn ^:no-doc key-cache-unpack!
  "Called on map keys when unpacking.
  Maintains key cache, returns nil."
  [cache_ clj-val]
  (when (or (keyword? clj-val) (string? clj-val))
    (swap! cache_
      (fn [m]
        (let [n (count m)]
          (if (<= n 255)
            (assoc m n clj-val)
            (do    m)))))
    nil))
