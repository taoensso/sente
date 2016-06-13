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
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.generic-node :as generic-node]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn- exp-req->ring-req
  "Transforms an Express req+resp to a ~standard Ring req map.
  `base-ring-req` is a partial Ring req map used to pass in route info."
  [base-ring-req exp-req exp-resp]
  (let [;; Query params from `express`:
        query-params (js->clj (.-query exp-req) :keywordize-keys true)
        ;; POST params from `body-parser` middleware:
        form-params  (js->clj (.-body  exp-req) :keywordize-keys true)
        ;; Ring exposes a merged view of params:
        params (merge query-params form-params)

        ring-req
        (merge base-ring-req
          {:response     exp-resp
           :body         exp-req
           :query-params query-params
           :form-params  form-params
           :params       params})]

    (tracef "Emulated Ring request: %s" ring-req)
    ring-req))

(defn- default-csrf-token-fn
  "Generates a CSRF token using the `csurf` middleware."
  [ring-req]
  (.csrfToken (:body ring-req)))

(defn make-express-channel-socket-server!
  "A customized `make-channel-socket-server!` that uses Node.js with
  Express as the web server."
  [& [opts]]
  (tracef "Making Express chsk")
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

         ;; TODO Fixme
         ;; Sente version: v1.9.0-SNAPSHOT
         ;; Error: `TypeError: Cannot convert object to primitive value`
         ;; This error is consistently getting generated every time an Ajax
         ;; client calls `chsk-send!` in the reference example. Suspect the
         ;; issue may have something to do with the `body-parser` middleware?

         (ajax-post-fn
          (exp-req->ring-req base-ring-req req resp)))})))
