(ns taoensso.sente.server-adapters.undertow
  "Sente server adapter for ring-undertow-adapter."
  {:author "Nik Peric"}
  (:require
    [clojure.core.async :as async]
    [ring.adapter.undertow.websocket :as websocket]
    [ring.adapter.undertow.response :as response]
    [taoensso.sente.interfaces :as i])
  (:import
    [io.undertow.websockets.core WebSocketChannel]
    [io.undertow.server HttpServerExchange]
    [io.undertow.websockets WebSocketConnectionCallback
                            WebSocketProtocolHandshakeHandler]))

;; Websocket
(extend-type WebSocketChannel
  i/IServerChan
  (sch-open? [this] (.isOpen this))
  (sch-close! [this] (.sendClose this))
  (sch-send! [this websocket? msg] (websocket/send msg this)))

(extend-protocol response/RespondBody
  WebSocketConnectionCallback
  (respond [body ^HttpServerExchange exchange]
    (let [handler (WebSocketProtocolHandshakeHandler. body)]
      (.handleRequest handler exchange))))

(defn- ws-ch
  [{:keys [on-open on-close on-msg on-error]}]
  (websocket/ws-callback {:on-open          (when on-open (fn [{:keys [channel]}] (on-open channel true)))
                          :on-error         (when on-error (fn [{:keys [channel error]}] (on-error channel true error)))
                          :on-message       (when on-msg (fn [{:keys [channel data]}] (on-msg channel true data)))
                          :on-close-message (when on-close (fn [{:keys [channel message]}]
                                                             (on-close channel true message)))}))

;; AJAX
(defprotocol ISenteUndertowAjaxChannel
  (close! [this])
  (send! [this msg])
  (read! [this]))

(deftype SenteUndertowAjaxChannel [ch open? on-close]
  ISenteUndertowAjaxChannel
  (close! [this]
    (when on-close (on-close ch false nil))
    (reset! open? false)
    (async/close! ch))
  (send! [this msg]
    (async/put! ch msg (fn [_] (close! this))))
  (read! [this]
    (async/<!! ch))

  i/IServerChan
  (sch-open? [this] @open?)
  (sch-close! [this] (close! this))
  (sch-send! [this websocket? msg] (send! this msg)))

(defn- ajax-ch [{:keys [on-open on-close]}]
  (let [ch      (async/chan 1)
        open?   (atom true)
        channel (SenteUndertowAjaxChannel. ch open? on-close)]
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
    {:body (if (:websocket? ring-req)
             (ws-ch callbacks-map)
             (ajax-ch callbacks-map))}))

(defn get-sch-adapter [] (UndertowServerChanAdapter.))
