(ns taoensso.msgpack
  "Clj/s MessagePack implementation adapted from `msgpack-cljc` by @rosejn."
  (:require
   [taoensso.msgpack.impl :as impl]
   [taoensso.msgpack.common :as c]))

(def ^:dynamic *auto-cache-keywords?*
  "Experimental, subject to change. Feedback welcome!

  When true, packing automatically caches repeated keyword occurrences. Keep
  false until all readers support the new encoding."
  false)

(def ^:dynamic *shared-dict*
  "Experimental, subject to change. Feedback welcome!

  Shared dictionary to use when packing/unpacking, nil by default.
  Prefer `:shared-dict` options where available."
  nil)

(defn shared-dict
  "Experimental, subject to change. Feedback welcome!

  Given a non-empty ordered sequence of up to 65536 distinct keywords and/or
  non-empty strings, returns an immutable precompiled dictionary for repeated
  use.

  A dictionary is part of the encoding: version and store its definition with
  your code, ordering frequent entries first (the first 256 use smaller refs).
  Unpacking requires the same ordered prefix. Never remove or reorder entries;
  appending is backwards compatible when readers are upgraded before writers.
  The fingerprint detects accidental schema mismatch; it is not cryptographic.
  Entries are referenced anywhere they occur, and decoded strings reuse the
  dictionary's instance. Payloads without dictionary references have no
  dictionary overhead. Deref to recover the entries."
  [entries]
  (c/shared-dict entries))

#?(:clj
   (defn pack
     "1 arity: returns MessagePack-encoded byte[] for given Clj value.
      2 arity: writes  MessagePack-encoded bytes  for given Clj value to
        given DataOutput and returns the DataOutput."
     (^bytes              [       clj] (c/with-pack-key-cache *auto-cache-keywords?* *shared-dict* (impl/pack clj)))
     (^java.io.DataOutput [output clj] (c/with-pack-key-cache *auto-cache-keywords?* *shared-dict* (impl/pack clj output))))

   :cljs
   (defn pack
     "1 arity: returns MessagePack-encoded Uint8Array for given Cljs value.
      2 arity: writes  MessagePack-encoded bytes      for given Cljs value to
        given output ∈ #{Uint8Array ArrayBuffer DataView subs} and returns an
        output stream that can be dereffed to get Uint8Array."
     ([       clj] (c/with-pack-key-cache *auto-cache-keywords?* *shared-dict* (impl/pack clj)))
     ([output clj] (c/with-pack-key-cache *auto-cache-keywords?* *shared-dict* (impl/pack clj output)))))

#?(:clj
   (defn unpack
     "Returns Clj value for given MessagePack-encoded input ∈ #{byte[] DataInput}.

     Optional limits default to 64 MiB per byte/string value, 1048576
     items per collection, and 512 levels of nesting. Set an option to nil
     to disable that limit. See `shared-dict` for the `:shared-dict` option."
     ([input] (c/with-unpack-key-cache *shared-dict* (impl/unpack input)))
     ([input {:keys [max-value-bytes max-collection-items max-nesting-depth] :as opts}]
      (let [shared-dict (if (contains? opts :shared-dict) (:shared-dict opts) *shared-dict*)]
        (c/with-unpack-key-cache shared-dict (impl/unpack input opts)))))

   :cljs
   (defn unpack
     "Returns Cljs value for given MessagePack-encoded input
     ∈ #{Uint8Array ArrayBuffer DataView subs}.
     Decoded binary values may share the input's backing buffer.

     Optional limits default to 64 MiB per byte/string value, 1048576
     items per collection, and 512 levels of nesting. Set an option to nil
     to disable that limit. See `shared-dict` for the `:shared-dict` option."
     ([input] (c/with-unpack-key-cache *shared-dict* (impl/unpack input)))
     ([input {:keys [max-value-bytes max-collection-items max-nesting-depth] :as opts}]
      (let [shared-dict (if (contains? opts :shared-dict) (:shared-dict opts) *shared-dict*)]
        (c/with-unpack-key-cache shared-dict (impl/unpack input opts))))))

(comment
  (require '[taoensso.encore :as enc])
  (let [x [nil {:a :A :b :B :c "foo", :v (vec (range 128)), :s (set (range 128))}]]
    (enc/qb 1e4 ; [988.04 197.36]
      (enc/read-edn (enc/pr-edn x))
      (unpack       (pack       x)))))
