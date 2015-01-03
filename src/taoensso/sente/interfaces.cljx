(ns taoensso.sente.interfaces
  "Experimental (pre-alpha): subject to change.
  Public interfaces / extension points."
  #+clj  (:require [clojure.tools.reader.edn :as edn])
  #+cljs (:require [cljs.reader              :as edn]))

;;;; Servers

#+clj (defprotocol IAsyncHTTPServer "TODO: Extension pt. for HTTP servers.")

;;;; Packers

(defprotocol IPacker
  "Extension pt. for client<->server comms data un/packers:
  arbitrary Clojure data <-> serialized strings."
  (pack   [_ x])
  (unpack [_ x]))

(deftype EdnPacker [clj-opts cljs-opts] ; Opts are EXPERIMENTAL
  IPacker
  (pack   [_ x] (pr-str x))
  ;; (unpack [_ s] (edn/read-string s)) ; Without opts
  (unpack [_ s]
    #+clj (edn/read-string (:reader-opts clj-opts) s)
    #+cljs
    (let [{:keys [reader-tag-table default-data-reader-fn]} cljs-opts]
      (if (and (nil? reader-tag-table) (nil? default-data-reader-fn))
        (edn/read-string s)
        (binding [cljs.reader/*tag-table*
                  (if-let [nv reader-tag-table]
                    (do (assert (instance? Atom nv)) nv)
                    cljs.reader/*tag-table*)

                  cljs.reader/*default-data-reader-fn*
                  (if-let [nv default-data-reader-fn]
                    (do (assert (instance? Atom nv)) nv)
                    cljs.reader/*default-data-reader-fn*)]
          (edn/read-string s))))))

(def     edn-packer "Default Edn packer." (->EdnPacker {} {}))
(defn coerce-packer [x] (if (= x :edn) edn-packer
                          (do (assert (satisfies? IPacker x)) x)))
