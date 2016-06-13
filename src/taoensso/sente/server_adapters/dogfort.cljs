(ns taoensso.sente.server-adapters.dogfort
  "Sente server adapter for Node.js with Dog Fort
  (https://github.com/whamtet/dogfort)."
  {:author "Matthew Molloy <@whamtet>"}
  (:require [taoensso.sente.server-adapters.generic-node :as generic-node]))

(defn get-sch-adapter
  "Dogfort doesn't need anything special, can just use the `generic-node-ws`
  adapter."
  [] (generic-node/get-sch-adapter))

(do ; DEPRECATED
  ;; These are stateful, could be problematic?
  (def dogfort-adapter "Deprecated" (get-sch-adapter))
  (def sente-web-server-adapter "Deprecated" dogfort-adapter))
