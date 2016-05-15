(ns taoensso.sente.packers.transit
  "Experimental - subject to change!
  Optional Transit-format[1] IPacker implementation for use with Sente.
  [1] https://github.com/cognitect/transit-format."
  {:author "Peter Taoussanis, @ckarlsen84"}

  #+clj
  (:require
   [clojure.string            :as str]
   [taoensso.encore           :as enc :refer (have have! have?)]
   [taoensso.timbre           :as timbre]
   [cognitect.transit         :as transit]
   [taoensso.sente.interfaces :as interfaces :refer (pack unpack)])

  #+clj
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream])

  #+cljs
  (:require
   [clojure.string    :as str]
   [taoensso.encore   :as enc :refer-macros (have have! have?)]
   [cognitect.transit :as transit]
   [taoensso.sente.interfaces :as interfaces :refer (pack unpack)]))

;;;; TODO
;; * Note that Transit-cljs doesn't seem to actually have msgpack support atm?
;; * Invesigate the actual cost of cljs+clj side writer/reader construction -
;;   is it worth caching these?
;; * Is it worth considering a cljs-side baos/bais pool?

(defn- get-charset [transit-fmt]
  ;; :msgpack appears to need ISO-8859-1 to retain binary data correctly when
  ;; string-encoded, all other (non-binary) formats can get UTF-8:
  (if (enc/kw-identical? transit-fmt :msgpack) "ISO-8859-1" "UTF-8"))

(def ^:private -transit-writer
  #+cljs (encore/memoize_ (fn [     fmt opts] (transit/writer      fmt opts)))
  #+clj                   (fn [baos fmt opts] (transit/writer baos fmt opts)))

(def ^:private -transit-reader
  #+cljs (encore/memoize_ (fn [     fmt opts] (transit/reader      fmt opts)))
  #+clj                   (fn [bais fmt opts] (transit/reader bais fmt opts)))

(deftype TransitPacker [transit-fmt writer-opts reader-opts]
  taoensso.sente.interfaces/IPacker
  (pack [_ x]
    #+cljs (transit/write (-transit-writer transit-fmt writer-opts) x)
    #+clj  (let [charset (get-charset transit-fmt)
                 ^ByteArrayOutputStream baos (ByteArrayOutputStream. 512)]
             (transit/write (-transit-writer baos transit-fmt writer-opts) x)
             (.toString baos ^String charset)))

  (unpack [_ s]
    #+cljs (transit/read (-transit-reader transit-fmt reader-opts) s)
    #+clj  (let [charset (get-charset transit-fmt)
                 ba (.getBytes ^String s ^String charset)
                 ^ByteArrayInputStream bais (ByteArrayInputStream. ba)]
             (transit/read (-transit-reader bais transit-fmt reader-opts)))))

(defn get-transit-packer "Returns a new TransitPacker"
  ([           ] (get-transit-packer :json       {} {}))
  ([transit-fmt] (get-transit-packer transit-fmt {} {}))
  ([transit-fmt writer-opts reader-opts]
   (have? [:el #{:json :msgpack}] transit-fmt)
   (have? map? writer-opts reader-opts)
   (TransitPacker. transit-fmt writer-opts reader-opts)))

(comment
  (def tp (get-transit-packer))
  (unpack tp (pack tp "foo")))
