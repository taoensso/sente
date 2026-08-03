(ns taoensso.sente.packers.impl.gzip
  (:require
   [taoensso.truss  :as truss]
   [taoensso.encore :as enc]))

(def ^:private ^:const prefix-raw  0x00)
(def ^:private ^:const prefix-gzip 0x01)
(def ^:private ^:const min-bytes   1024)
(def ^:const default-max-uncompressed-bytes (* 64 1024 1024))

(defn- size-error [size max-size]
  (ex-info "Gzip payload exceeds configured expansion limit"
    {:uncompressed-bytes size, :max-uncompressed-bytes max-size}))

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

(defn- stream->u8s
  ([readable] (stream->u8s readable nil))
  ([readable max-size]
   (let [reader  (.getReader readable)
         chunks  (array)
         total_  (volatile! 0)
         finish
         (fn []
           (let [out (js/Uint8Array. @total_)]
             (loop [idx 0, offset 0]
               (if (< idx (.-length chunks))
                 (let [chunk (aget chunks idx)]
                   (.set out chunk offset)
                   (recur (inc idx) (+ offset (.-byteLength chunk))))
                 out))))]
     (letfn [(step []
               (-> (.read reader)
                 (.then
                   (fn [result]
                     (if (.-done result)
                       (finish)
                       (let [chunk (.-value result)
                             total (+ @total_ (.-byteLength chunk))]
                         (when (and max-size (> total max-size))
                           (.cancel reader)
                           (throw (size-error total max-size)))
                         (vreset! total_ total)
                         (.push chunks chunk)
                         (step)))))))]
       (step)))))

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
  ([u8s] (gunzip u8s default-max-uncompressed-bytes))
  ([^js u8s max-size]
   (let [len  (.-byteLength u8s)
         flag (aget u8s 0)
         body (.subarray u8s 1)]
     (cond
       (zero? len)
       (js/Promise.reject (ex-info "Invalid empty gzip payload" {}))

       (not (or (== flag prefix-raw) (== flag prefix-gzip)))
       (js/Promise.reject (ex-info "Invalid gzip payload prefix" {:prefix flag}))

       (== flag prefix-raw)
       (if (and max-size (> (.-byteLength body) max-size))
         (js/Promise.reject (size-error (.-byteLength body) max-size))
         (js/Promise.resolve body))

       :else
       (let [rs  (.-body (js/Response. body))
             ds  (js/DecompressionStream. "gzip")
             out (.pipeThrough rs ds)]
         (stream->u8s out max-size))))))
