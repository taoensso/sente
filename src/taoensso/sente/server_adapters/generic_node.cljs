(ns taoensso.sente.server-adapters.generic-node
  "Sente server adapter for Node.js using the `ws` and `http`
  libraries"
  {:author "Andrew Phillips <@theasp>, Matthew Molloy <@whamtet>"}
  (:require
   [taoensso.sente.interfaces :as i]
   [taoensso.timbre :as timbre
    :refer-macros (tracef debugf infof warnf errorf)]))

(defn- is-ws-open? [ws] (= (.-readyState ws) (.-OPEN ws)))

(deftype GenericNodeWsAdapter [callbacks-map ws]
  i/IServerChan
  (sch-open?  [sch] (is-ws-open? ws))
  (sch-close! [sch]
    (let [pre-open? (is-ws-open? ws)]
      (.close ws)
      pre-open?))

  (-sch-send! [sch msg close-after-send?]
    (let [pre-open? (is-ws-open? ws)]
      (try
        (.send ws msg)
        (catch :default e nil))

      (when close-after-send? (.close ws))
      pre-open?)))

(defn- make-ws-chan
  [{:keys [on-open on-close on-msg] :as callbacks-map} ws]
  (tracef "Making websocket adapter")
  (let [chan (new GenericNodeWsAdapter callbacks-map ws)]
    (on-open chan)
    (.on      ws "message" (fn [data flags]   (on-msg   chan data)))
    (.onclose ws           (fn [code message] (on-close chan code)))))

(deftype GenericNodeAjaxAdapter [response-open? response]
  i/IServerChan
  (sch-open?  [this] @response-open?)
  (sch-close! [this]
    (when @response-open?
      (.end response)
      (reset! response-open? false)
      true))

  (-sch-send! [this msg close-after-send?]
    (let [pre-open? @response-open?]
      (if close-after-send?
        (do
          (reset! response-open? false)
          (.end response msg))
        (try
          (.write response msg)
          (catch :default e nil)))
      pre-open?)))

(defn- make-ajax-chan
  [{:keys [on-open on-close on-msg] :as callbacks-map} response body]
  (tracef "Making ajax adapter")
  (let [response-open? (atom true)
        chan (GenericNodeAjaxAdapter. response-open? response)]
    (on-open chan)
    (.on body "data" (fn [data] (on-msg chan data)))

    ;; TODO: If this is bad, what should we do?
    #_(.on response ; Bad
           "finish"
           #(on-close chan)))

  ;; If we reply blank for Ajax conns then the route matcher will fail.
  ;; Dog Fort will send a 404 resp and close the conn. To keep it open
  ;; we just send this instead of a Ring resp. Shouldn't have a bad
  ;; effect on other servers.
  {:keep-alive true})

(deftype GenericNodeServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    (let [{:keys [websocket response body]} ring-req]
      (if websocket
        (make-ws-chan callbacks-map websocket)
        (make-ajax-chan callbacks-map response body)))))

(def generic-node-adapter (GenericNodeServerChanAdapter.))

(def sente-web-server-adapter
  "Alias for ns import convenience"
  generic-node-adapter)
