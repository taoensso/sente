(ns taoensso.sente.server-adapters.community.undertow
  {:author "Nik Peric"}
  (:require
    [ring.adapter.undertow.websocket :as websocket]
    [ring.adapter.undertow.response  :as response]
    [taoensso.sente.interfaces :as i]
    [taoensso.trove :as trove])
  (:import
    [io.undertow.websockets.core WebSocketChannel]
    [io.undertow.server HttpServerExchange]
    [io.undertow.websockets
     WebSocketConnectionCallback
     WebSocketProtocolHandshakeHandler]))

;;;; WebSockets

(extend-type WebSocketChannel
  i/IServerChan
  (sch-open?  [sch] (.isOpen    sch))
  (sch-close! [sch] (.sendClose sch))
  (sch-send!  [sch websocket? msg]
    (websocket/send msg sch)
    (i/sch-open?        sch)))

(extend-protocol response/RespondBody
  WebSocketConnectionCallback
  (respond [body ^HttpServerExchange exchange]
    (let [handler (WebSocketProtocolHandshakeHandler. body)]
      (.handleRequest handler exchange))))

(defn- ws-ch
  [ring-req {:keys [on-open on-close on-msg on-error]} _adapter-opts]
  (websocket/ws-callback
    {:on-open          (when on-open  (fn [{:keys [channel]}]         (on-open  channel true)))
     :on-error         (when on-error (fn [{:keys [channel error]}]   (on-error channel true error)))
     :on-message       (when on-msg   (fn [{:keys [channel data]}]    (on-msg   channel true data)))
     :on-close-message (when on-close (fn [{:keys [channel message]}] (on-close channel true message)))}))

;;;; Ajax

(defprotocol ISenteUndertowAjaxChannel
  (ajax-read! [sch]))

(deftype SenteUndertowAjaxChannel [ring-req resp-promise_ open?_ on-close adapter-opts]
  i/IServerChan
  (sch-send!  [sch websocket? msg] (deliver resp-promise_ msg) (i/sch-close! sch))
  (sch-open?  [sch] @open?_)
  (sch-close! [sch]
    (when (compare-and-set! open?_ true false)
      (deliver resp-promise_ nil)
      (when on-close (on-close sch false nil))
      true))

  ISenteUndertowAjaxChannel
  (ajax-read! [sch]
    (let [{:keys [ajax-resp-timeout-ms on-ajax-resp-timeout]} adapter-opts
          resp
          (if ajax-resp-timeout-ms
            (deref resp-promise_ ajax-resp-timeout-ms ::timeout)
            (deref resp-promise_))]

      (if (= resp ::timeout)
        (when-let [f on-ajax-resp-timeout] (f sch ring-req))
        resp))))

(defn- ajax-ch
  [ring-req {:keys [on-open on-close]} adapter-opts]
  (let [open?_ (atom true)
        sch
        (SenteUndertowAjaxChannel. ring-req (promise) open?_
          on-close adapter-opts)]

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
       (ws-ch   ring-req callbacks-map adapter-opts)
       (ajax-ch ring-req callbacks-map adapter-opts))}))

(defn get-sch-adapter
  "Returns a Sente `ServerChan` adapter for `ring-undertow-adapter` [1].

  Options:
        `:ajax-resp-timeout-ms` - Max msecs to wait for Ajax responses (default 60 secs)
     `:on-ajax-resp-timeout`    - (fn [ServerChan ring-req]) to trigger after above timeout (logs by default)

  [1] Ref. <https://github.com/luminus-framework/ring-undertow-adapter>."
  ([] (get-sch-adapter nil))
  ([{:as   opts
     :keys [ajax-resp-timeout-ms on-ajax-resp-timeout]
     :or
     {   ajax-resp-timeout-ms (* 60 1000)
      on-ajax-resp-timeout
      (fn [sch ring-req]
        (trove/log!
          {:level :warn, :id :sente.server.undertow/ajax-read-timeout,
           :data ring-req}))}}]

   (UndertowServerChanAdapter.
     (assoc opts
          :ajax-resp-timeout-ms ajax-resp-timeout-ms
       :on-ajax-resp-timeout on-ajax-resp-timeout))))
