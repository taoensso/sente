(ns taoensso.sente.server-adapters.undertow
  "Sente server adapter for ring-undertow-adapter.
   Modified to avoid core.async and use promise directly and with read timeout"
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

(def ajax-response-timeout-ms (* 60 1000))

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

(deftype SenteUndertowAjaxChannel [promised-response open?_ on-close]
  ISenteUndertowAjaxChannel
  (send!  [this msg] (deliver promised-response msg))
  (read!  [this]
    (let [resp (deref promised-response ajax-response-timeout-ms :lp-timed-out)]
      (close! this)
      resp))
  (close! [this]
    (when @open?_
      (reset! open?_ false)
      (when on-close (on-close this false nil))
      true))

  i/IServerChan
  (sch-send!  [this websocket? msg] (send! this msg))
  (sch-open?  [this] @open?_)
  (sch-close! [this] (close! this)))

(defn- ajax-ch [{:keys [on-open on-close]}]
  (let [promised-response (promise)
        open?_  (atom true)
        channel (SenteUndertowAjaxChannel. promised-response open?_ on-close)]
    (when on-open (on-open channel false))
    channel))

(extend-protocol response/RespondBody
  SenteUndertowAjaxChannel
  (respond [body ^HttpServerExchange exchange]
    (response/respond (read! body) exchange)))

;; Adapter
(deftype UndertowServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    ;; Returns {:body <websocket-implementation-channel> ...}:
    {:body
     (if (:websocket? ring-req)
       (ws-ch   callbacks-map)
       (ajax-ch callbacks-map))}))

(defn get-sch-adapter [] (UndertowServerChanAdapter.))
