(ns taoensso.sente.packers.impl.gzip
  (:require
   [taoensso.truss  :as truss]
   [taoensso.encore :as enc]))

(def ^:private ^:const prefix-raw  0x00)
(def ^:private ^:const prefix-gzip 0x01)
(def ^:private ^:const min-bytes   1024)

(defn- as-u8s
  "#{Uint8Array ArrayBuffer DataView} -> Uint8Array"
  ^js [input]
  (cond
    (instance? js/Uint8Array  input)                 input
    (instance? js/ArrayBuffer input) (js/Uint8Array. input)
    (instance? js/DataView    input) (js/Uint8Array. (.-buffer     input)
                                                     (.-byteOffset input)
                                                     (.-byteLength input))
    :else
    (truss/ex-info! "Unexpected input type"
      {:type (enc/type-name input)
       :expected '#{Uint8Array ArrayBuffer DataView}})))

(defn- prefix
  "Returns given Uint8Array with added flag prefix."
  [gzip? ^js u8s]
  (let [out (js/Uint8Array. (inc (.-length u8s)))]
    (aset out 0 (if gzip? prefix-gzip prefix-raw))
    (.set out u8s 1)
    (do   out)))

(defn- stream->u8s [readable] (-> (js/Response. readable) (.arrayBuffer) (.then #(js/Uint8Array. %))))

(defn gzip
  "Uncompressed Uint8Array -> compressed Uint8Array Promise."
  [^js u8s]
  (if (< (.-length u8s) min-bytes)
    (js/Promise.resolve (prefix false u8s))
    (let [rs  (.-body (js/Response. u8s))
          ts  (js/CompressionStream. "gzip")
          out (.pipeThrough rs ts)]
      (-> (stream->u8s out) (.then #(prefix true %))))))

(defn gunzip
  "Compressed Uint8Array -> uncompressed Uint8Array Promise."
  [^js u8s]
  (let [flag (aget      u8s 0)
        body (.subarray u8s 1)]
    (if-not (== flag prefix-gzip)
      (js/Promise.resolve body)
      (let [rs   (.-body (js/Response. body))
            ds   (js/DecompressionStream. "gzip")
            out  (.pipeThrough rs ds)]
        (stream->u8s out)))))
