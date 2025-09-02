(ns taoensso.sente.packers.edn
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.sente.interfaces :as i]))

(defn get-packer
  "Returns Sente packer that uses the EDN text format.
  A reasonable default choice for most users."
  []
  (reify i/IPacker2
    (pack   [_ ws? clj    cb-fn] (cb-fn {:value (enc/pr-edn      clj)}))
    (unpack [_ ws? packed cb-fn] (cb-fn {:value (enc/read-edn packed)}))))

(comment
  (let [p (get-packer)]
    (i/pack p :ws [:chsk/ws-ping "foo"]
      (fn [{packed :value}]
        (i/unpack p :ws packed
          (fn [{clj :value}] clj))))))
