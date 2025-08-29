(ns taoensso.sente.packers.msgpack
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.truss   :as truss]
   [taoensso.msgpack :as msgpack]
   [taoensso.sente.interfaces :as i]))

(defn get-packer
  "Returns Sente packer that uses the binary MessagePack
  format, Ref. <https://msgpack.org/index.html>.

  Clj/s MessagePack implementation adapted from
  <https://github.com/rosejn/msgpack-cljc>.

  Experimental, please test carefully and report any issues!"
  []
  (reify i/IPacker2
    (pack   [_ _ws? x  cb-fn] (cb-fn {:value (msgpack/pack    x)}))
    (unpack [_ _ws? ba cb-fn] (cb-fn {:value (msgpack/unpack ba)}))))

(comment
  (let [p (get-packer)]
    (i/pack p :ws [:chsk/ws-ping "foo"]
      (fn [{packed :value}]
        (i/unpack p :ws packed
          (fn [{clj :value}] clj))))))
