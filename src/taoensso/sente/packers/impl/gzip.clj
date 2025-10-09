(ns taoensso.sente.packers.impl.gzip)

(def ^:private ^:const prefix-raw  (byte 0x00))
(def ^:private ^:const prefix-gzip (byte 0x01))
(def ^:private ^:const min-bytes   1024)

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
  ^bytes [^bytes ba]
  (let [len   (alength ba)
        flag  (bit-and 0xFF (aget ba 0))]
    (if-not (== flag prefix-gzip)
      (java.util.Arrays/copyOfRange ba 1 len)
      (with-open [bais (java.io.ByteArrayInputStream.  ba 1 (dec len))
                  gis  (java.util.zip.GZIPInputStream. bais)
                  baos (java.io.ByteArrayOutputStream.)]
        (.transferTo gis baos)
        (.toByteArray    baos)))))

(comment
  (defn bn [len] (byte-array (take len (cycle (range 128)))))

  (let [ba-sm (bn 128)
        ba-lg (bn 2048)]
    (taoensso.encore/qb 1e4 ; [3.84 170.84]
      (gunzip (gzip ba-sm))
      (gunzip (gzip ba-lg))))

  (take 16 (gunzip (gzip (bn 128))))
  (take 16 (gunzip (gzip (bn 2048)))))
