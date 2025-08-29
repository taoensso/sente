(ns taoensso.msgpack.interfaces
  #?(:cljs (:require-macros [taoensso.msgpack.interfaces])))

(defprotocol Packable    (pack-bytes [clj out]))
(defrecord   PackableExt [byte-id ba-content])

(defmulti  unpack-ext      (fn [byte-id ba-content]               byte-id))
(defmethod unpack-ext :default [byte-id ba-content] (PackableExt. byte-id ba-content))

#?(:clj
   (defmacro extend-packable [byte-id class & [pack-clause unpack-clause]]
     (let [pack-clause
           (when-let [[_ args form] pack-clause]
             `(extend-protocol Packable ~class
                (pack-bytes [~@args out#] (pack-bytes (PackableExt. ~byte-id ~form) out#))))

           unpack-clause
           (when-let [[_ args form] unpack-clause]
             `(defmethod unpack-ext ~byte-id [~'byte-id ~@args] ~form))]

       `(do ~pack-clause ~unpack-clause))))
