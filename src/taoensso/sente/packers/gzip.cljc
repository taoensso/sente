(ns taoensso.sente.packers.gzip
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.truss  :as truss]
   [taoensso.encore :as enc]
   [taoensso.sente.packers.impl.gzip :as impl]
   [taoensso.sente.interfaces :as i]))

(defn wrap-packer
  "Experimental, please test carefully and report any issues!

  Returns Sente packer that wraps another with gzip compression.
  Needs `js/CompressionStream` browser support.

  If `packer` takes+returns platform bytes:   `binary?` should be true.
  If `packer` takes+returns platform strings: `binary?` should be false."

  [packer {:keys [binary?]}]
  #?(:clj
     (reify i/IPacker2
       (unpack [_ ws? ba cb]
         (if binary?
           (i/unpack packer ws?                   (impl/gunzip ba)  cb)
           (i/unpack packer ws? (enc/utf8-ba->str (impl/gunzip ba)) cb)))

       (pack [_ ws? x cb]
         (i/pack packer ws? x
           (fn [{:keys [value error]}]
             (if           error
               (cb {:error error})
               (cb {:value (impl/gzip (if binary? value (enc/str->utf8-ba value)))}))))))

     :cljs
     (do
       (when-not (exists? js/CompressionStream)   (truss/ex-info!   "CompressionStream not supported"))
       (when-not (exists? js/DecompressionStream) (truss/ex-info! "DecompressionStream not supported"))
       (reify i/IPacker2
         (unpack [_ ws? packed cb]
           (->
             (impl/gunzip (impl/as-u8s packed)) ; Decompress -> u8s promise
             (.then
               (fn [u8s]
                 (if binary?
                   (i/unpack packer ws?                   u8s  cb)
                   (i/unpack packer ws? (enc/utf8-ba->str u8s) cb))))
             (.catch (fn [err] (cb {:error err})))))

         (pack [_ ws? cljs-val cb]
           (i/pack packer ws? cljs-val
             (fn [{:keys [error value]}] ; Serialize -> text/bin value
               (if           error
                 (cb {:error error})
                 (-> (impl/gzip (if binary? (impl/as-u8s value) (enc/str->utf8-ba value))) ; Compress -> u8s promise
                   (.then  (fn [u8s] (cb {:value u8s})))
                   (.catch (fn [err] (cb {:error err}))))))))))))

(comment
  (let [p (wrap-packer (taoensso.sente.packers.msgpack/get-packer) {:binary? true})
        #_(wrap-packer (taoensso.sente.packers.edn/get-packer)     {:binary? false})]
    (i/pack p :ws [:chsk/ws-ping "foo"]
      (fn [{packed :value}]
        (i/unpack p :ws packed
          (fn [{clj :value}] clj))))))
