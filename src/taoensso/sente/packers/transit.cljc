(ns taoensso.sente.packers.transit
  "Alpha - subject to change!
  Optional Transit-format[1] IPacker implementation for use with Sente.
  [1] https://github.com/cognitect/transit-format."
  {:author "Peter Taoussanis, @ckarlsen84"}

  #?(:clj
     (:require
      [clojure.string :as str]
      [taoensso.encore :as enc :refer (have have! have?)]
      [taoensso.timbre :as timbre]
      [cognitect.transit :as transit]
      [taoensso.sente.interfaces :as interfaces :refer (pack unpack)]))

  #?(:clj
     (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

  #?(:cljs
     (:require
      [clojure.string :as str]
      [taoensso.encore :as enc :refer-macros (have have! have?)]
      [cognitect.transit :as transit]
      [taoensso.sente.interfaces :as interfaces :refer (pack unpack)])))

#?(:clj
   (defn- get-charset [transit-fmt]
     ;; :msgpack appears to need ISO-8859-1 to retain binary data correctly when
     ;; string-encoded, all other (non-binary) formats can get UTF-8:
     (if (enc/kw-identical? transit-fmt :msgpack) "ISO-8859-1" "UTF-8")))

#?(:clj
   (def ^:private cache-read-handlers
     "reader-opts -> reader-opts with cached read handler map"
     (let [cache (enc/memoize_ (fn [m] (transit/read-handler-map m)))]
       (fn [reader-opts]
         (if-let [m (:handlers reader-opts)]
           (assoc reader-opts :handlers (cache m))
           reader-opts)))))

#?(:clj
   (def ^:private cache-write-handlers
     "writer-opts -> writer-opts with cached write handler map"
     (let [cache (enc/memoize_ (fn [m] (transit/write-handler-map m)))]
       (fn [writer-opts]
         (if-let [m (:handlers writer-opts)]
           (assoc writer-opts :handlers (cache m))
           writer-opts)))))

#?(:clj
   (def ^:private transit-writer-fn-proxy
     (enc/thread-local-proxy
       (fn [fmt opts]
         (let [^String charset (get-charset fmt)
               opts (cache-write-handlers opts)
               ^ByteArrayOutputStream baos (ByteArrayOutputStream. 64)
               writer (transit/writer baos fmt opts)]
           (fn [x]
             (transit/write writer x)
             (let [result (.toString baos charset)]
               (.reset baos)
               result)))))))

(def ^:private get-transit-writer-fn
  "Returns thread-safe (fn [x-to-write])"
  #?(:cljs
     (enc/memoize_
       (fn [fmt opts]
         (let [writer (transit/writer fmt opts)]
           (fn [x] (transit/write writer x)))))
     :clj
     (fn [fmt opts]
       (let [thread-local-transit-writer-fn (.get ^ThreadLocal transit-writer-fn-proxy)]
         (thread-local-transit-writer-fn fmt opts)))))

(def ^:private get-transit-reader-fn
  "Returns thread-safe (fn [str-to-read])"
  #?(:cljs
     (enc/memoize_
       (fn [fmt opts]
         (let [reader (transit/reader fmt opts)]
           (fn [s] (transit/read reader s)))))
     :clj
     (fn [fmt opts]
       (let [^String charset (get-charset fmt)
             opts (cache-read-handlers opts)]
         (fn [s]
           (let [ba (.getBytes ^String s ^String charset)
                 ^ByteArrayInputStream bais (ByteArrayInputStream. ba)
                 reader (transit/reader bais fmt opts)]
             (transit/read reader)))))))

(deftype TransitPacker [transit-fmt writer-opts reader-opts]
  taoensso.sente.interfaces/IPacker
  (pack   [_ x] ((get-transit-writer-fn transit-fmt writer-opts) x))
  (unpack [_ s] ((get-transit-reader-fn transit-fmt reader-opts) s)))

(defn get-transit-packer "Returns a new TransitPacker"
  ([           ] (get-transit-packer :json       {} {}))
  ([transit-fmt] (get-transit-packer transit-fmt {} {}))
  ([transit-fmt writer-opts reader-opts]
   ;; No transit-cljs support for msgpack atm
   (have? [:el #{:json #_:msgpack}] transit-fmt)
   (have? map? writer-opts reader-opts)
   (TransitPacker. transit-fmt writer-opts reader-opts)))

(comment
  (def tp (get-transit-packer))
  (enc/qb 10000
    (unpack tp (pack tp [:chsk/ws-ping "foo"]))
    (enc/read-edn (enc/pr-edn [:chsk/ws-ping "foo"]))))
