(ns taoensso.sente.server-adapters.community.generic-node
  "Sente server adapter for Node.js using the `ws` and `http` libraries.
  Ref. <https://github.com/websockets/ws>,
       <https://nodejs.org/api/http.html>,
       <https://nodejs.org/en/docs/guides/anatomy-of-an-http-transaction>,
       <https://github.com/theasp/sente-nodejs-example>."
  {:author "Andrew Phillips <@theasp>, Matthew Molloy <@whamtet>"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.timbre :as timbre]
   [taoensso.sente.interfaces :as i]))

(defn- ws-open? [ws] (= (.-readyState ws) (.-OPEN ws)))

(deftype GenericNodeWsAdapter [callbacks-map ws]
  i/IServerChan
  (sch-open?  [sch]       (ws-open? ws))
  (sch-close! [sch] (when (ws-open? ws) (.close ws) true))
  (sch-send!  [sch websocket? msg]
    (when (ws-open? ws)
      (let [close-after-send? (if websocket? false true)
            sent?
            (try
              (.send ws msg (fn ack [?error]))
              true
              (catch :default _ nil))]
        (when close-after-send? (.close ws))
        sent?))))

(defn- make-ws-chan [callbacks-map ws]
  (timbre/trace "Making WebSocket adapter")
  (let [chan (GenericNodeWsAdapter. callbacks-map ws) ; sch
        {:keys [on-open on-close on-msg _on-error]} callbacks-map
        ws? true]
    ;; (debugf "WebSocket debug: %s" [(type ws) ws])
    (when on-msg   (.on ws "message" (fn [data flags] (on-msg   chan ws? data))))
    (when on-close (.on ws "close"   (fn [code msg]   (on-close chan ws? code))))
    (when on-open  (do                                (on-open  chan ws?)))
    ws))

(deftype GenericNodeAjaxAdapter [resp-open?_ resp]
  i/IServerChan
  (sch-open?  [sch] @resp-open?_)
  (sch-close! [sch]
    (when (compare-and-set! resp-open?_ true false)
      (.end resp)
      true))

  (sch-send! [sch websocket? msg]
    (if-let [close-after-send? (if websocket? false true)]
      (when (compare-and-set! resp-open?_ true false)
        (.end resp msg)
        true)

      ;; Currently unused since `close-after-send?` will always
      ;; be true for Ajax connections
      (when @resp-open?_
        (try
          (.write resp msg (fn callback []))
          true
          (catch :default _ nil))))))

(defn- make-ajax-chan [callbacks-map req resp]
  ;; req  - IncomingMessage
  ;; resp - ServerResponse
  (timbre/trace "Making Ajax adapter")
  (let [resp-open?_ (atom true)
        chan (GenericNodeAjaxAdapter. resp-open?_ resp) ; sch
        {:keys [on-open on-close on-msg _on-error]} callbacks-map
        ws? false]

    ;; (debugf "Ajax debug: %s" [[(type req) req] [(type resp) resp]])
    ;; (debugf "Ajax request: %s, %s" (aget req "method") (aget req "url"))

    (when on-close
      (.on resp "finish" (fn [] (on-close chan ws? nil)))
      (.on resp "close"  (fn [] (on-close chan ws? nil))))
    (when on-open               (on-open  chan ws?))

    ;; If we reply blank for Ajax conns then the route matcher will fail.
    ;; Dog Fort will send a 404 resp and close the conn. To keep it open
    ;; we just send this instead of a Ring resp. Shouldn't have a bad
    ;; effect on other servers.
    {:keep-alive true}))

(deftype GenericNodeServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    (if-let [ws (:websocket ring-req)]
      (make-ws-chan   callbacks-map ws)
      (make-ajax-chan callbacks-map
        (:body     ring-req)
        (:response ring-req)))))

(defn get-sch-adapter [] (GenericNodeServerChanAdapter.))

(enc/deprecated
  ;; These are stateful, could be problematic?
  (def generic-node-adapter     "Deprecated" (get-sch-adapter))
  (def sente-web-server-adapter "Deprecated" generic-node-adapter))
