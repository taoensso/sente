(ns taoensso.sente.server-adapters.dogfort
  "Sente on node.js with dogfort (https://github.com/whamtet/dogfort)"
  {:author "Matthew Molloy <whamtet@gmail.com>"}
  (:require
   [taoensso.sente.server-adapters.generic-node :as generic-node]))

;; Dogfort doesn't need anything special, use use the generic-node-ws
;; adapter.
(def dogfort-adapter generic-node/generic-node-adapter)
(def sente-web-server-adapter dogfort-adapter)
