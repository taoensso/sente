(ns taoensso.sente.server-adapters.express
  "Sente server adapter for Node.js with Express (http://expressjs.com/).

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
   [taoensso.timbre :as timbre]
   [taoensso.sente  :as sente]
   [taoensso.sente.server-adapters.generic-node :as generic-node]))

(defn- obj->map
  "Workaround for `TypeError: Cannot convert object to primitive value`s
  caused by `(js->clj (.-body  exp-req) :keywordize-keys true)` apparently
  failing to correctly identify `(.-body exp-req)` as an object. Not sure
  what's causing this problem."
  [o]
  (when-let [ks (js-keys o)]
    (into {} (for [k ks] [(keyword k) (str (aget o k))]))))

(defn- exp-req->ring-req
  "Transforms an Express req+resp to a ~standard Ring req map.
  `base-ring-req` is a partial Ring req map used to pass in route info."
  [base-ring-req exp-req exp-resp]
  (let [query-params (obj->map (.-query exp-req)) ; From `express`
        form-params  (obj->map (.-body  exp-req)) ; From `body-parser`
        params (merge query-params form-params)
        ring-req
        (merge base-ring-req
          {:response     exp-resp
           :body         exp-req
           :query-params query-params
           :form-params  form-params
           :params       params})]

    (timbre/tracef "Emulated Ring request: %s" ring-req)
    ring-req))

(defn- default-csrf-token-fn
  "Generates a CSRF token using the `csurf` middleware."
  [ring-req]
  (.csrfToken (:body ring-req)))

(defn make-express-channel-socket-server!
  "A customized `make-channel-socket-server!` that uses Node.js with
  Express as the web server."
  [& [opts]]
  (timbre/trace "Making Express chsk server")
  (let [default-opts {:csrf-token-fn default-csrf-token-fn}
        chsk (sente/make-channel-socket-server!
              (generic-node/get-sch-adapter)
              (merge default-opts opts))

        {:keys [ajax-get-or-ws-handshake-fn ajax-post-fn]} chsk]

    (merge chsk
      {:ajax-get-or-ws-handshake-fn
       (fn [req resp & [_ base-ring-req]]
         (ajax-get-or-ws-handshake-fn
          (exp-req->ring-req base-ring-req req resp)))

       :ajax-post-fn
       (fn [req resp & [_ base-ring-req]]
         (ajax-post-fn
          (exp-req->ring-req base-ring-req req resp)))})))
