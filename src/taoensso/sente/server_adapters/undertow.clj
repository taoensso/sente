(ns taoensso.sente.server-adapters.undertow
  "Sente server adapter for ring-undertow-adapter."
  {:author "Nik Peric"}
  (:require
    [ring.adapter.undertow.websocket :as websocket]
    [ring.adapter.undertow.response  :as response]
    [taoensso.sente.interfaces :as i])
  (:import
    [io.undertow.websockets.core WebSocketChannel]
    [io.undertow.server HttpServerExchange]
    [io.undertow.websockets
     WebSocketConnectionCallback
     WebSocketProtocolHandshakeHandler]))

;; Websocket
(extend-type WebSocketChannel
  i/IServerChan
  (sch-open?  [this] (.isOpen    this))
  (sch-close! [this] (.sendClose this))
  (sch-send!  [this websocket? msg] (websocket/send msg this)))

(extend-protocol response/RespondBody
  WebSocketConnectionCallback
  (respond [body ^HttpServerExchange exchange]
    (let [handler (WebSocketProtocolHandshakeHandler. body)]
      (.handleRequest handler exchange))))

(defn- ws-ch
  [{:keys [on-open on-close on-msg on-error]}]
  (websocket/ws-callback
    {:on-open          (when on-open  (fn [{:keys [channel]}]         (on-open  channel true)))
     :on-error         (when on-error (fn [{:keys [channel error]}]   (on-error channel true error)))
     :on-message       (when on-msg   (fn [{:keys [channel data]}]    (on-msg   channel true data)))
     :on-close-message (when on-close (fn [{:keys [channel message]}] (on-close channel true message)))}))

;; AJAX
(defprotocol ISenteUndertowAjaxChannel
  (send!  [this msg])
  (read!  [this])
  (close! [this]))

(deftype SenteUndertowAjaxChannel [promised-response_ open?_ on-close opts]
  ISenteUndertowAjaxChannel
  (send!  [this msg] (deliver promised-response_ msg))
  (read!  [this]
    (let [{:keys [ajax-response-timeout-ms ajax-response-timeout-val]
           :or {ajax-response-timeout-val :undertow/ajax-response-timeout}} opts
          resp (if ajax-response-timeout-ms
                 (deref promised-response_ ajax-response-timeout-ms ajax-response-timeout-val)
                 @promised-response_)]
      (close! this)
      resp))
  (close! [this]
    (when (compare-and-set! open?_ true false)
      (when on-close (on-close this false nil))
      true))

  i/IServerChan
  (sch-send!  [this websocket? msg] (send! this msg))
  (sch-open?  [this] @open?_)
  (sch-close! [this] (close! this)))

(defn- ajax-ch [{:keys [on-open on-close]} opts]
  (let [promised-response_ (promise)
        open?_  (atom true)
        channel (SenteUndertowAjaxChannel. promised-response_ open?_ on-close opts)]
    (when on-open (on-open channel false))
    channel))

(extend-protocol response/RespondBody
  SenteUndertowAjaxChannel
  (respond [body ^HttpServerExchange exchange]
    (response/respond (read! body) exchange)))

;; Adapter
(deftype UndertowServerChanAdapter [opts]
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    ;; Returns {:body <websocket-implementation-channel> ...}:
    {:body
     (if (:websocket? ring-req)
       (ws-ch   callbacks-map)
       (ajax-ch callbacks-map opts))}))

(defn get-sch-adapter
  "Undertow adapter specific options avoiding possible connection leaks and worker pool starvation:
     :ajax-response-timeout-ms ; Timeout waiting for Ajax response after given msecs
     :ajax-response-timeout-val ; The value returned in case of above timeout"
  ([] (UndertowServerChanAdapter. nil))
  ([opts] (UndertowServerChanAdapter. opts)))
