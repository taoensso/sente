(ns taoensso.msgpack
  "Clj/s MessagePack implementation adapted from `msgpack-cljc` by @rosejn."
  (:require
   [taoensso.msgpack.impl :as impl]
   [taoensso.msgpack.common :as c]))

#?(:clj
   (defn pack
     "1 arity: returns MessagePack-encoded byte[] for given Clj value.
      2 arity: writes  MessagePack-encoded bytes  for given Clj value to
        given DataOutput and returns the DataOutput."
     (^bytes              [       clj] (c/with-key-cache (impl/pack clj)))
     (^java.io.DataOutput [output clj] (c/with-key-cache (impl/pack clj output))))

   :cljs
   (defn pack
     "1 arity: returns MessagePack-encoded Uint8Array for given Cljs value.
      2 arity: writes  MessagePack-encoded bytes      for given Cljs value to
        given output ∈ #{Uint8Array ArrayBuffer DataView subs} and returns an
        output stream that can be dereffed to get Uint8Array."
     ([       clj] (c/with-key-cache (impl/pack clj)))
     ([output clj] (c/with-key-cache (impl/pack clj output)))))

#?(:clj
   (defn unpack
     "Returns Clj value for given MessagePack-encoded input ∈ #{byte[] DataInput}."
     [input] (c/with-key-cache (impl/unpack input)))

   :cljs
   (defn unpack
     "Returns Cljs value for given MessagePack-encoded input
     ∈ #{Uint8Array ArrayBuffer DataView subs}."
     [input] (c/with-key-cache (impl/unpack input))))

(comment
  (require '[taoensso.encore :as enc])
  (let [x [nil {:a :A :b :B :c "foo", :v (vec (range 128)), :s (set (range 128))}]]
    (enc/qb 1e4 ; [988.04 197.36]
      (enc/read-edn (enc/pr-edn x))
      (unpack       (pack       x)))))
