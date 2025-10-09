(ns example.dynamic-packer
  "A dynamic Sente packer that can dynamically switch between a variety
  of underlying packers. Handy for testing, though you wouldn't normally
  need/want something like this in production!"
  (:require
   [taoensso.encore :as encore]
   [taoensso.sente  :as sente]
   [taoensso.sente.interfaces :as i]
   [taoensso.sente.packers.transit]
   [taoensso.sente.packers.msgpack]
   [taoensso.sente.packers.gzip]))

(defonce mode_ (atom :edn/txt))

(defn- str->bytes [s]
  #?(:clj  (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.-buffer (.encode (js/TextEncoder.) s))))

(defn- bytes->str [b]
  #?(:clj  (String. ^bytes b java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.decode (js/TextDecoder. "utf-8") (js/Uint8Array. b))))

(comment (-> "foo" str->bytes bytes->str))

(defn get-packer []
  (let [ep sente/edn-packer
        bp ; Simple binary edn packer
        (reify
          i/IPacker2
          (pack   [_ ws? clj    cb-fn] (cb-fn {:value (str->bytes (encore/pr-edn clj))}))
          (unpack [_ ws? packed cb-fn] (cb-fn {:value (encore/read-edn (bytes->str packed))})))

        tp    (taoensso.sente.packers.transit/get-packer)
        mp    (taoensso.sente.packers.msgpack/get-packer)
        mp+gz (taoensso.sente.packers.gzip/wrap-packer mp {:binary? true})

        get-packer
        (fn []
          (case @mode_
            :edn/txt    ep
            :edn/bin    bp
            :transit    tp
            :msgpack    mp
            :msgpack+gz mp+gz))]

    (reify i/IPacker2
      (pack   [_ ws? clj    cb-fn] (i/pack   (get-packer) ws? clj    cb-fn))
      (unpack [_ ws? packed cb-fn] (i/unpack (get-packer) ws? packed cb-fn)))))
