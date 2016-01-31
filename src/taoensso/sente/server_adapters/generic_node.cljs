(ns taoensso.sente.server-adapters.generic-node
  "Sente on node.js using ws and http libraries"
  {:author "Andrew Phillips <theasp@gmail.com> & Matthew Molloy <whamtet@gmail.com>"}
  (:require
   [taoensso.sente.interfaces :as i]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]))

(defn- is-ws-open? [ws]
  (= (.-readyState ws)
     (.-OPEN ws)))

(deftype GenericNodeWsAdapter [callbacks-map ws]
  i/IServerChan
  (-sch-send! [this msg close-after-send?]
    (let [pre-open? (is-ws-open? ws)]
      (try
        (.send ws msg)
        (catch :default e))
      (when close-after-send?
        (.close ws))
      pre-open?))

  (sch-open? [server-ch]
    (is-ws-open? ws))

  (sch-close! [server-ch]
    (let [pre-open? (is-ws-open? ws)]
      (.close ws)
      pre-open?)))

(defn- make-ws-chan
  [{:keys [on-open on-close on-msg] :as callbacks-map} ws]
  (tracef "Making websocket adapter")
  (let [chan (new GenericNodeWsAdapter callbacks-map ws)]
    (on-open chan)
    (.on ws "message"
         (fn [data flags]
           (on-msg chan data)))
    (.onclose ws
              (fn [code message]
                (on-close chan code)))))

(deftype GenericNodeAjaxAdapter [response-open? response]
  i/IServerChan
  (-sch-send! [this msg close-after-send?]
    (let [pre-open? @response-open?]
      (if close-after-send?
        (do
          (reset! response-open? false)
          (.end response msg))
        (try
          (.write response msg)
          (catch :default e)))
      pre-open?))

  (sch-open? [this]
    @response-open?)

  (sch-close! [this]
    (if @response-open?
      (.end response))
    (reset! response-open? false)))

(defn- make-ajax-chan
  [{:keys [on-open on-close on-msg] :as callbacks-map} response body]
  (tracef "Making ajax adapter")
  (let [response-open? (atom true)
        chan (new GenericNodeAjaxAdapter response-open? response)]
    (on-open chan)
    (.on body "data"
         (fn [data]
           (on-msg chan data)))
    ;; TODO: If this is bad, bad, what should we do?
    #_(.on response ;bad, bad!
           "finish"
           #(on-close chan)))
  ;; For AJAX connections, if we reply blank then the route
  ;; matcher will fail. dogfort will send a 404 response and
  ;; close the connection.  to keep it open we just send this
  ;; instead of a ring response.  This should have no
  ;; detrimental effect on other servers.
  {:keep-alive true})

(deftype GenericNodeServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    (let [{:keys [websocket response body]} ring-req]
      (if websocket
        (make-ws-chan callbacks-map websocket)
        (make-ajax-chan callbacks-map response body)))))

(def generic-node-adapter (GenericNodeServerChanAdapter.))
