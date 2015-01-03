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

(deftype EdnPacker [opts]
  IPacker
  (pack   [_ x] (pr-str x))
  (unpack [_ s] (edn/read-string opts s)))

(def     edn-packer "Default Edn packer." (->EdnPacker {}))
(defn coerce-packer [x] (if (= x :edn) edn-packer
                          (do (assert (satisfies? IPacker x)) x)))
