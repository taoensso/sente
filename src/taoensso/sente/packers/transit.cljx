(ns taoensso.sente.packers.transit
  "Experimental (pre-alpha): subject to change.
  Optional Transit-format[1] IPacker implementation for use with Sente.
  [1] https://github.com/cognitect/transit-format."
  {:author "Peter Taoussanis, @ckarlsen84"}

  #+clj
  (:require [clojure.string :as str]
            [clojure.tools.reader.edn  :as edn]
            [taoensso.encore           :as encore]
            [taoensso.timbre           :as timbre]
            [cognitect.transit         :as transit]
            [taoensso.sente.interfaces :as interfaces :refer (pack unpack)])

  #+clj
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream])

  #+cljs
  (:require [clojure.string    :as str]
            [cljs.reader       :as edn]
            [taoensso.encore   :as encore :refer (format)]
            [cognitect.transit :as transit]
            [taoensso.sente.interfaces :as interfaces :refer (pack unpack)]))

;; TODO Nb note that Transit-cljs doesn't seem to actually have msgpack support
;; for the moment

(defn- get-charset [transit-fmt]
  ;; :msgpack appears to need ISO-8859-1 to retain binary data correctly when
  ;; string-encoded, all other (non-binary) formats can get UTF-8:
  (if (= transit-fmt :msgpack) "ISO-8859-1" "UTF-8"))

(deftype TransitPacker [transit-fmt]
  taoensso.sente.interfaces/IPacker
  (pack [_ x]
    #+cljs (transit/write (transit/writer transit-fmt) x)
    #+clj  (let [charset (get-charset transit-fmt)
                 ^ByteArrayOutputStream baos (ByteArrayOutputStream. 512)]
             (transit/write (transit/writer baos transit-fmt) x)
             (.toString baos ^String charset)))

  (unpack [_ s]
    #+cljs (transit/read (transit/reader transit-fmt) s)
    #+clj  (let [charset (get-charset transit-fmt)
                 ba (.getBytes ^String s ^String charset)
                 ^ByteArrayInputStream bais (ByteArrayInputStream. ba)]
             (transit/read (transit/reader bais transit-fmt)))))

(def ^:private edn-packer     interfaces/edn-packer) ; Alias
(def ^:private json-packer    (->TransitPacker :json))
;; (def ^:private msgpack-packer (->TransitPacker :msgpack))

;;;; FlexiPacker

(defn- max-flexi-format? [fmt] (= fmt :json #_:msgpack))
(def ^:private max-flexi-format
  (let [ordered-formats [nil :edn :json #_:msgpack]
        scored-formats  (zipmap ordered-formats (next (range)))]
    (fn [xs] (apply max-key scored-formats xs))))

(comment (max-flexi-format [#_:msgpack :json :edn]))

(defn- auto-flexi-format [x]
  (cond
    (string? x) ; Large strings are common for HTML, etc.
    (let [c (count x)]
      (cond ;; (> c 500) :msgpack
            (> c 300) :json))

    (and (sequential? x) (counted? x))
    (let [c (count x)]
      (cond ;; (> c 50) :msgpack
            (> c 20) :json
            ;; TODO Try heuristically? (check random sample, etc.)
            ))))

(comment (auto-flexi-format (take 100 (range))))

(deftype FlexiPacker [default-fmt]
  taoensso.sente.interfaces/IPacker
  (pack [_ x]
    (let [?meta-format (when-let [m (meta x)]
                         (max-flexi-format (filter m (keys m))))
          ?auto-format (when-not ?meta-format (auto-flexi-format x))
          ;; ?auto-format (when-not (max-flexi-format? ?meta-format)
          ;;                (auto-flexi-format x))
          fmt (max-flexi-format [?auto-format ?meta-format default-fmt])]
      (case fmt
        ;; :msgpack (str "m" (pack msgpack-packer x))
        :json    (str "j" (pack json-packer    x))
        :edn     (str "e" (pack edn-packer     x)))))

  (unpack [_ s]
    (let [prefix (encore/substr s 0 1)
          s*     (encore/substr s 1)]
      (case prefix
        ;; "m" (unpack msgpack-packer s*)
        "j" (unpack json-packer    s*)
        "e" (unpack edn-packer     s*)
        (throw (ex-info (str "Malformed FlexiPacker data: " s)
                 {:s s}))))))

(defn get-flexi-packer
  "Experimental (pre-alpha): subject to change.
  Returns an IPacker implementation that un/packs data with a variable format
  determined by the data's size, metadata, or the provided `default-fmt` when no
  metadata is present.

  (def fpack (partial pack (get-flexi-packer :edn)))
  (fpack ^:edn     {:a :A :b :B}) => \"e{:a :A, :b :B}\"
  (fpack ^:json    {:a :A :b :B}) => \"j[\"^ \",\"~:a\",\"~:A\",\"~:b\",\"~:B\"]\"
  (fpack ^:msgpack {:a :A :b :B}  => \"m\202£~:a£~:A£~:b£~:B\""

  [& [?default-fmt]]
  (let [default-fmt (or ?default-fmt :edn)]
    (assert (#{:edn ; Not a transit format
               ;; Transit formats:
               :json :json-verbose #_:msgpack} default-fmt))
    (->FlexiPacker default-fmt)))

(def default-flexi-packer (get-flexi-packer :edn))

(comment
  (let [fpacker (get-flexi-packer)]
    (def fpack   (partial pack   fpacker))
    (def funpack (partial unpack fpacker)))
  (count   (fpack ^:edn     {:a :A :b :B}))
  (count   (fpack ^:json    {:a :A :b :B}))
  (count   (fpack ^:msgpack {:a :A :b :B}))
  (funpack (fpack ^:msgpack {:a :A :b :B :utf8 "ಬಾ ಇಲ್ಲಿ ಸಂಭವಿಸ"})))

(comment ; Packer benchmarks
  (let [data
        {:sm "Hello this is just a small string"
         :md {:a :A :b :B :c :C :d "This is a slightly larger datum, yo"}
         :lg ^:json ; ^:msgpack
         {:a "ahjkhfkjdhfkjdhfjkhdfjkhdjkfhdfkjhdfkjsdsfsifsuifuiosudfd"
          :b "fdjhfkjdhfjkdhfjkdhfjkhdjfkhdjkfhdfjkhsfsfiueiuiuiufiuiid"
          :c "fdjhfs[pdopoeiroejlkjfdklfjdkjfkdjfkldsfdfueiuiyqqqhahdhf"
          :d "fdkjoiwueuoiuwdm,sn,mndfdifdiofudfuoidfdfdfdfe3iuqiiuausj"
          :e "ejhfiurhiuui2ureoiuoieuroiueoirueioureisfdfddjghiuyiuyeyu"
          :f [1 383 398498 2 9 3389 893 9 309 290349 3782 1273 4447 933]
          :g #{:a :b :c :d :e :f :g :h :hello/foo :hello/bar :hello/baz}}}

        data    (:lg data) ; <-- Tweak input data size here
        size    (fn [packer] (count (pack packer data)))
        bench   (fn [packer] (encore/round (encore/qbench 10000
                                            (unpack packer (pack packer data)))))]

    {:size {:edn     (size edn-packer)
            :json    (size json-packer)
            ;; :msgpack (size msgpack-packer)
            :flexi   (size (get-flexi-packer))}
     :time {:edn     (bench edn-packer)
            :json    (bench json-packer)
            ;; :msgpack (bench msgpack-packer)
            :flexi   (bench (get-flexi-packer))}})

  {:size {:edn 35,   :json 43,   :msgpack 41},
   :time {:edn 81,   :json 316,  :msgpack 515}}
  {:size {:edn 63,   :json 86,   :msgpack 67},
   :time {:edn 228,  :json 284,  :msgpack 613}}
  {:size {:edn 448,  :json 510,  :msgpack 444,  :flexi 445},
   :time {:edn 3027, :json 1054, :msgpack 2054, :flexi 2213}})
