(ns taoensso.sente.server-adapters.community.macchiato
  "Sente server adapter for Node.js with the Macchiato Framework,
  Ref. <https://github.com/macchiato-framework/macchiato-core>."
  {:author "Andrew Phillips <@theasp>"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.timbre :as timbre]
   [taoensso.sente  :as sente]
   [taoensso.sente.server-adapters.community.generic-node :as generic-node]
   [macchiato.middleware.anti-forgery :as csrf]))

(def csrf-path [:session :macchiato.middleware.anti-forgery/anti-forgery-token])

(defn wrap-macchiato
  "Wraps a generic node Sente handler to work with Macchiato.  This
  remaps some keys of a Macchiato request to match what Sente and the
  generic node adapter are expecting, calling `handler`.  The generic
  node adapter will call the appropriate methods on the Node.js response
  object without using Macchiato's response function."
  [handler]
  (fn [req res raise]
    (-> req
        (assoc :response (:node/response req))
        (assoc-in [:session :csrf-token] (get-in req csrf-path))
        (handler))))

(defn make-macchiato-channel-socket-server!
  "A customized `make-channel-socket-server!` that uses Node.js with
  Macchiato as the web server."
  [& [opts]]
  (timbre/trace "Making Macchiato chsk server")
  (-> (generic-node/get-sch-adapter)
      (sente/make-channel-socket-server! opts)
      (update :ajax-get-or-ws-handshake-fn wrap-macchiato)
      (update :ajax-post-fn wrap-macchiato)))
