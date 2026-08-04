(ns taoensso.sente.packers.msgpack
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.truss   :as truss]
   [taoensso.msgpack :as msgpack]
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
    Remaining options are forwarded to `taoensso.msgpack/unpack`."
  ([] (get-packer nil))
  ([opts]
   (let [auto-cache-keywords? (get opts :auto-cache-keywords?)
         auto-cache-option?   (contains? opts :auto-cache-keywords?)
         unpack-opts          (dissoc opts :auto-cache-keywords?)]
     (reify i/IPacker2
       (pack [_ _ws? x cb]
         (let [packed (if auto-cache-option?
                        (binding [msgpack/*auto-cache-keywords?* auto-cache-keywords?]
                          (msgpack/pack x))
                        (msgpack/pack x))]
           (cb {:value packed})))
       (unpack [_ _ws? in cb] (cb {:value (msgpack/unpack in unpack-opts)}))))))

(comment
  (let [p (get-packer)]
    (i/pack p :ws [:chsk/ws-ping "foo"]
      (fn [{packed :value}]
        (i/unpack p :ws packed
          (fn [{clj :value}] clj))))))
