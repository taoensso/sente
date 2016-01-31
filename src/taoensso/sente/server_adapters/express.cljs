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
  "Transform the express request `exp-req` and response `exp-resp`
  into a map similar to what ring would produce, merged ontop of the
  map `base-ring-req` which is used to pass in information from the
  route.  This map does not contain all the fields that a normal
  `ring-req` would."
  [exp-req ;; Express request object
   exp-resp ;; Express response object
   base-ring-req ;; Map the ring request will be based on
   ]
  (let [;; Query params from `express`
        query-params (js->clj (.-query exp-req) :keywordize-keys true)
        ;; POST params from `body-parser` middleware
        form-params (js->clj (.-body exp-req) :keywordize-keys true)

        ring-req
        (merge base-ring-req
          {:response     exp-resp
           :body         exp-req
           :query-params query-params
           :form-params  form-params
           ;; Ring exposes a merged view of params
           :params       (merge query-params form-params)})]

    (tracef "Emulated Ring request: %s" ring-req)
    ring-req))

(defn- default-csrf-token-fn
  "Generate a CSRF token using the `csurf` middleware."
  [ring-req]
  (.csrfToken (:body ring-req)))

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
                ajax-post-fn]} ch]

    (merge ch
      {:ajax-get-or-ws-handshake-fn
       (fn [req resp & [_ base-ring-req]]
         (ajax-get-or-ws-handshake-fn (->ring-req req resp base-ring-req)))

       :ajax-post-fn
       (fn [req resp & [_ base-ring-req]]
         (ajax-post-fn (->ring-req req resp base-ring-req)))})))
