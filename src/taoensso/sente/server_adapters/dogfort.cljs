(ns taoensso.sente.server-adapters.dogfort
  "Sente server adapter for Node.js with Dog Fort
  (https://github.com/whamtet/dogfort)"
  {:author "Matthew Molloy <@whamtet>"}
  (:require
   [taoensso.sente.server-adapters.generic-node :as generic-node]))

(def dogfort-adapter
  "Dogfort doesn't need anything special, can just use the
  `generic-node-ws` adapter"
  generic-node/generic-node-adapter)

(def sente-web-server-adapter
  "Alias for ns import convenience"
  dogfort-adapter)
