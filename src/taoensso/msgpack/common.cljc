(ns taoensso.msgpack.common
  #?(:cljs (:require-macros [taoensso.msgpack.common])))

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
