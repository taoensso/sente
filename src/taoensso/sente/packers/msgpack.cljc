(ns taoensso.sente.packers.msgpack
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.truss   :as truss]
   [taoensso.msgpack :as msgpack]
   [taoensso.msgpack.common :as c]
   [taoensso.sente.interfaces :as i]))

(defn get-packer
  "Experimental, please test carefully and report any issues!

  Returns Sente packer that uses the binary MessagePack
  format, Ref. <https://msgpack.org/index.html>.

  Clj/s MessagePack implementation adapted from
  <https://github.com/rosejn/msgpack-cljc>.

  Options:
    `:auto-cache-keywords?` - enables experimental keyword caching; use only
      after all readers support the new encoding
    `:shared-dict` - enables experimental shared dictionary; precompiled
      `taoensso.msgpack/shared-dict` that must match on all readers and writers
    `:pack-shared-dict` - dictionary (or nil) to pack with while unpacking
      with `:shared-dict`; lets peers learn a new dictionary before any use it
    Remaining options are forwarded to `taoensso.msgpack/unpack`."
  ([] (get-packer nil))
  ([opts]
   (c/as-shared-dict (get opts      :shared-dict)) ; Validate eagerly
   (c/as-shared-dict (get opts :pack-shared-dict))
   (let [unpack-opts (dissoc opts :auto-cache-keywords? :pack-shared-dict)]
     (reify i/IPacker2
       (pack [_ _ws? x cb]
         (cb {:value
              (binding [msgpack/*auto-cache-keywords?*
                        (get opts :auto-cache-keywords? msgpack/*auto-cache-keywords?*)

                        msgpack/*shared-dict*
                        (get opts :pack-shared-dict
                          (get opts :shared-dict msgpack/*shared-dict*))]
                (msgpack/pack x))}))
       (unpack [_ _ws? in cb] (cb {:value (msgpack/unpack in unpack-opts)}))))))

(comment
  (let [p (get-packer)]
    (i/pack p :ws [:chsk/ws-ping "foo"]
      (fn [{packed :value}]
        (i/unpack p :ws packed
          (fn [{clj :value}] clj))))))
