(ns taoensso.sente.server-adapters.express
  "Sente on node.js with express"
  {:author "Andrew Phillips <theasp@gmail.com>"}
  (:require 
   [taoensso.sente     :as sente]
   [taoensso.sente.server-adapters.generic-node :as generic-node]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]))

;; This adapter works differently that the others as sente is
;; expecting ring requests but express uses http.IncomingMessage.
;; While most of this adapter could be used for similar
;; implementations there will be assumptions here that the following
;; express middleware, or equivalents, ae in place:
;; - cookie-parser
;; - body-parser
;; - csurf
;; - express-session
;; - express-ws

;; See example-project for an implementation, as it's a bit
;; different than something built on ring.

(defn- make-ring-req [req res ring-req]
  "Emulate req as used by the ring library by processing the "
  (let [fake-params (merge (js->clj (.-params req) :keywordize-keys true)
                           (js->clj (.-body req) :keywordize-keys true)
                           (js->clj (.-query req) :keywordize-keys true)
                           (:query ring-req))
        ring-req (assoc ring-req
                        :response res
                        :body req
                        :params fake-params)]
    (tracef "Emulated ring request: %s" ring-req)
    ring-req))


(defn wrap-ring-req [ring-fn req res ring-req]
  "Run a function that takes a ring request by converting a
  req, res, and fake ring-req map"
  (let [ring-req (make-ring-req req res ring-req)]
    (ring-fn ring-req)))

(defn csrf-token [ring-req]
  "Get a valid token from csurf"
  (.csrfToken (:body ring-req)))

(def default-options {:csrf-token-fn csrf-token})

(defn make-express-adapter
  "Provide a custom make-channel-socket-server! that wraps calls with
  wrap-ring-req as the functions from the real
  make-channel-socket-server! require ring-reqs.  As a bonus you don't
  need to specify the adapter."
  [options]
  (tracef "Making express adapter")
  (let [ch (sente/make-channel-socket-server!
            (generic-node/GenericNodeServerChanAdapter.)
            (merge default-options options))]

    (assoc ch
           :ajax-get-or-ws-handshake-fn
           (fn [req res & [next ring-req]]
             (wrap-ring-req (:ajax-get-or-ws-handshake-fn ch) req res ring-req))
           
           :ajax-post-fn
           (fn [req res & [next ring-req]]
             (wrap-ring-req (:ajax-post-fn ch) req res ring-req)))))
