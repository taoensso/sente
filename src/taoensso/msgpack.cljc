(ns taoensso.msgpack
  "Clj/s MessagePack implementation adapted from `msgpack-cljc` by @rosejn."
  (:require
   [taoensso.msgpack.impl :as impl]
   [taoensso.msgpack.common :as c]))

#?(:clj
   (defn pack
     "1 arity: returns MessagePack-encoded byte[] for given Clj value.
      2 arity: writes  MessagePack-encoded bytes  for given Clj value to
        given output ∈ #{DataOutput OutputStream} and returns output."
     (^bytes [clj] (c/with-key-cache (impl/pack clj)))
     ([output clj] (c/with-key-cache (impl/pack clj output)) output))

   :cljs
   (defn pack
     "1 arity: returns MessagePack-encoded Uint8Array for given Cljs value.
      2 arity: writes  MessagePack-encoded bytes      for given Cljs value to
        given output ∈ #{ArrayBuffer} and returns output."
     ([       clj] (c/with-key-cache (impl/pack clj)))
     ([output clj] (c/with-key-cache (impl/pack clj output)) output)))

#?(:clj
   (defn unpack
     "Returns Clj value for given MessagePack-encoded payload ∈ #{byte[] DataInput InputStream}."
     [packed] (c/with-key-cache (impl/unpack packed)))

   :cljs
   (defn unpack
     "Returns Cljs value for given MessagePack-encoded payload ∈ #{Uint8Array}."
     [packed] (c/with-key-cache (impl/unpack packed))))
