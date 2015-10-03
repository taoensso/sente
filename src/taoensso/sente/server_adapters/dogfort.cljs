(ns taoensso.sente.server-adapters.dogfort
  "Sente on node.js with dogfort (https://github.com/whamtet/dogfort)"
  {:author "Matthew Molloy (whamtet@gmail.com)"}
  (:require
   [taoensso.sente.server-adapters.generic-node-ws :as generic-node-ws]))

;; Dogfort doesn't need anything special, use use the generic-node-ws
;; adapter.
(def sente-web-server-adapter generic-node-ws/sente-web-server-adapter)
