(ns taoensso.sente.server-adapters.undertow
  "Sente server adapter for ring-undertow-adapter."
  {:author "Nik Peric"}
  (:require
    [clojure.core.async :as async]
    [ring.adapter.undertow.websocket :as websocket]
    [ring.adapter.undertow.response  :as response]
    [taoensso.sente.interfaces :as i])
  (:import
    [io.undertow.websockets.core WebSocketChannel]
    [io.undertow.server HttpServerExchange]
    [io.undertow.websockets
     WebSocketConnectionCallback
     WebSocketProtocolHandshakeHandler]))

;;;; WebSocket

(extend-type WebSocketChannel
  i/IServerChan
  (sch-open?  [sch] (.isOpen    sch))
  (sch-close! [sch] (.sendClose sch))
  (sch-send!  [sch websocket? msg] (websocket/send msg sch)))

(extend-protocol response/RespondBody
  WebSocketConnectionCallback
  (respond [body ^HttpServerExchange exchange]
    (let [handler (WebSocketProtocolHandshakeHandler. body)]
      (.handleRequest handler exchange))))

(defn- ws-ch
  [{:keys [on-open on-close on-msg on-error]} _adapter-opts]
  (websocket/ws-callback
    {:on-open          (when on-open  (fn [{:keys [channel]}]         (on-open  channel true)))
     :on-error         (when on-error (fn [{:keys [channel error]}]   (on-error channel true error)))
     :on-message       (when on-msg   (fn [{:keys [channel data]}]    (on-msg   channel true data)))
     :on-close-message (when on-close (fn [{:keys [channel message]}] (on-close channel true message)))}))

;;;; Ajax

(defprotocol ISenteUndertowAjaxChannel
  (ajax-read! [sch]))

(deftype SenteUndertowAjaxChannel [resp-ch open?_ on-close _adapter-opts]
  i/IServerChan
  (sch-send!  [sch websocket? msg] (async/put! resp-ch msg (fn [_] (i/sch-close! sch))))
  (sch-open?  [sch] @open?_)
  (sch-close! [sch]
    (when on-close (on-close sch false nil))
    (reset! open?_ false)
    (async/close! resp-ch))

  ISenteUndertowAjaxChannel
  (ajax-read! [sch] (async/<!! resp-ch)))

(defn- ajax-ch [{:keys [on-open on-close]} adapter-opts]
  (let [resp-ch (async/chan 1)
        open?_  (atom true)
        sch     (SenteUndertowAjaxChannel. resp-ch open?_ on-close
                  adapter-opts)]

    (when on-open (on-open sch false))
    sch))

(extend-protocol response/RespondBody
  SenteUndertowAjaxChannel
  (respond [body ^HttpServerExchange exchange]
    (response/respond (ajax-read! body) exchange)))

;;;; Adapter

(deftype UndertowServerChanAdapter [adapter-opts]
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    {:body
     (if (:websocket? ring-req)
       (ws-ch   callbacks-map adapter-opts)
       (ajax-ch callbacks-map adapter-opts))}))

(defn get-sch-adapter
  ([    ] (get-sch-adapter nil))
  ([opts] (UndertowServerChanAdapter. opts)))
