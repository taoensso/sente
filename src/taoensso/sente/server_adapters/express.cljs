(ns taoensso.sente.server-adapters.express
  "Sente server adapter for Node.js with Express
  (http://expressjs.com/)

  This adapter works differently that the others as Sente is
  expecting Ring requests but Express uses http.IncomingMessage.
  While most of this adapter could be used for similar
  implementations there will be assumptions here that the following
  express middleware (or equivalents) are in place:
    - cookie-parser
    - body-parser
    - csurf
    - express-session
    - express-ws

  See the example project at https://goo.gl/lnkiqS for an
  implementation (it's a bit different than something built on Ring)."
  {:author "Andrew Phillips <@theasp>"}
  (:require
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.generic-node :as generic-node]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn- ->ring-req
  [req ; TODO (from @ptaoussanis): what is this exactly? An Express request?
       ; Can we call it `exp-req` to help disambiguate?
   resp
   ring-req ; TODO (from @ptaoussanis): what is this? Are we not trying to
            ; produce a Ring request as output? Maybe we can find a clearer
            ; name for this?
   ]
  (let [;; TODO (from @ptaoussanis): could I ask for a comment re: what
        ;; these are for / why they're being called fake?
        fake-params
        (merge
          (js->clj (.-params req) :keywordize-keys true)
          (js->clj (.-body   req) :keywordize-keys true)
          (js->clj (.-query  req) :keywordize-keys true)
          (:query ring-req))

        ring-req
        (merge ring-req
          {:response resp
           :body     req
           :params   fake-params})]

    (tracef "Emulated Ring request: %s" ring-req)
    ring-req))

(defn- default-csrf-token-fn [ring-req] (.csrfToken (:body ring-req)))

(defn make-express-channel-socket-server!
  "A customized `make-channel-socket-server!` that uses Node.js with
  Express as the web server"
  [& [opts]]
  (tracef "Making express chsk")
  (let [default-opts {:csrf-token-fn default-csrf-token-fn}
        ch (sente/make-channel-socket-server!
             (generic-node/GenericNodeServerChanAdapter.)
             (merge default-opts opts))

        {:keys [ajax-get-or-ws-handshake-fn
                ajax-post-fn]} cn]

    (merge ch
      {:ajax-get-or-ws-handshake-fn
       (fn [req resp & [_ ring-req]]
         (ajax-get-or-ws-handshake-fn (->ring-req req resp ring-req)))

       :ajax-post-fn
       (fn [req resp & [_ ring-req]]
         (ajax-post-fn (->ring-req req resp ring-req)))})))
