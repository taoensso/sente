(ns taoensso.sente.packers.impl.gzip)

(def ^:private ^:const prefix-raw  (byte 0x00))
(def ^:private ^:const prefix-gzip (byte 0x01))
(def ^:private ^:const min-bytes   1024)
(def ^:const default-max-uncompressed-bytes (* 64 1024 1024))

(defn- check-size! [size max-size]
  (when (and max-size (> size max-size))
    (throw
      (ex-info "Gzip payload exceeds configured expansion limit"
        {:uncompressed-bytes size, :max-uncompressed-bytes max-size})))
  size)

(defn- prefix
  "Returns given byte[] with added flag prefix."
  ^bytes [gzip? ^bytes ba]
  (let [len (alength ba)
        out (byte-array (inc len))]
    (aset-byte             out 0 (if gzip? prefix-gzip prefix-raw))
    (System/arraycopy ba 0 out 1 len)
    (do                    out)))

(defn gzip
  "Uncompressed byte[] -> compressed byte[]."
  ^bytes [^bytes ba]
  (let [len (alength ba)]
    (if (< len min-bytes)
      (prefix false ba)
      (with-open [baos (java.io.ByteArrayOutputStream.)]
        (.write baos (int prefix-gzip))
        (with-open [gos (java.util.zip.GZIPOutputStream. baos)]
          (.write gos ba 0 len))
        (.toByteArray baos)))))

(defn gunzip
  "Compressed byte[] -> uncompressed byte[]."
  (^bytes [^bytes ba] (gunzip ba default-max-uncompressed-bytes))
  (^bytes [^bytes ba max-size]
   (let [len (alength ba)]
     (when (zero? len)
       (throw (ex-info "Invalid empty gzip payload" {})))
     (let [flag (bit-and 0xFF (aget ba 0))]
       (when-not (or (== flag prefix-raw) (== flag prefix-gzip))
         (throw (ex-info "Invalid gzip payload prefix" {:prefix flag})))
       (if (== flag prefix-raw)
         (do
           (check-size! (dec len) max-size)
           (java.util.Arrays/copyOfRange ba 1 len))
         (with-open [bais (java.io.ByteArrayInputStream. ba 1 (dec len))
                     gis  (java.util.zip.GZIPInputStream. bais)
                     baos (java.io.ByteArrayOutputStream.)]
           (let [buffer (byte-array 8192)]
             (loop [total 0]
               (let [n (.read gis buffer)]
                 (when-not (neg? n)
                   (let [total (+ total n)]
                     (check-size! total max-size)
                     (.write baos buffer 0 n)
                     (recur total))))))
           (.toByteArray baos)))))))

(comment
  (defn bn [len] (byte-array (take len (cycle (range 128)))))

  (let [ba-sm (bn 128)
        ba-lg (bn 2048)]
    (taoensso.encore/qb 1e4 ; [3.84 170.84]
      (gunzip (gzip ba-sm))
      (gunzip (gzip ba-lg))))

  (take 16 (gunzip (gzip (bn 128))))
  (take 16 (gunzip (gzip (bn 2048)))))
