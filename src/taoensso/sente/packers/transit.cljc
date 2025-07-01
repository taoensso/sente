(ns taoensso.sente.packers.transit
  "Optional Transit-format[1] IPacker implementation for use with Sente.
  [1] https://github.com/cognitect/transit-format."
  {:author "Peter Taoussanis, @ckarlsen84"}
  (:require
   [taoensso.encore   :as enc]
   [taoensso.truss    :as truss]
   [cognitect.transit :as transit]
   [taoensso.sente.interfaces :as interfaces
    :refer [pack unpack]])

  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

#?(:clj
   (defn- get-charset ^String [transit-fmt]
     ;; :msgpack appears to need ISO-8859-1 to retain binary data correctly when
     ;; string-encoded, all other (non-binary) formats can get UTF-8:
     (if (enc/identical-kw? transit-fmt :msgpack) "ISO-8859-1" "UTF-8")))

#?(:clj
   (def ^:private cache-read-handlers
     "reader-opts -> reader-opts with cached read handler map"
     (let [cache (enc/fmemoize (fn [m] (transit/read-handler-map m)))]
       (fn [reader-opts]
         (if-let [m (:handlers reader-opts)]
           (assoc reader-opts :handlers (cache m))
           reader-opts)))))

#?(:clj
   (def ^:private cache-write-handlers
     "writer-opts -> writer-opts with cached write handler map"
     (let [cache (enc/fmemoize (fn [m] (transit/write-handler-map m)))]
       (fn [writer-opts]
         (if-let [m (:handlers writer-opts)]
           (assoc writer-opts :handlers (cache m))
           writer-opts)))))

#?(:clj
   (def ^:private transit-writer-fn-proxy
     (enc/thread-local-proxy
       (fn [fmt opts]
         (let [charset (get-charset fmt)
               opts    (cache-write-handlers opts)
               baos    (ByteArrayOutputStream. 64)
               writer  (transit/writer baos fmt opts)]
           (fn [x]
             (transit/write writer x)
             (let [result (.toString baos charset)]
               (.reset baos)
               result)))))))

(def ^:private get-transit-writer-fn
  "Returns thread-safe (fn [x-to-write])"
  #?(:cljs
     (enc/fmemoize
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
     (enc/fmemoize
       (fn [fmt opts]
         (let [reader (transit/reader fmt opts)]
           (fn [s] (transit/read reader s)))))
     :clj
     (fn [fmt opts]
       (let [charset (get-charset fmt)
             opts    (cache-read-handlers opts)]
         (fn [^String s]
           (let [ba     (.getBytes s charset)
                 bais   (ByteArrayInputStream. ba)
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
   (truss/have? [:el #{:json :json-verbose #_:msgpack}] transit-fmt)
   (truss/have? map? writer-opts reader-opts)
   (TransitPacker. transit-fmt writer-opts reader-opts)))

(comment
  (def tp (get-transit-packer))
  (enc/qb 1e4 ; [139.36 71.1]
    (do   (unpack tp (pack tp [:chsk/ws-ping "foo"])))
    (enc/read-edn (enc/pr-edn [:chsk/ws-ping "foo"]))))
