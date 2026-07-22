(ns taoensso.sente.java-ws-client
  (:require
   [taoensso.trove :as trove]
   [taoensso.sente.interfaces :as i])

  (:import
   [java.nio ByteBuffer]
   [org.java_websocket.client WebSocketClient]))

(defn- byte-buffer->ba ^bytes [^ByteBuffer bb]
  (let [in (.slice bb)
        ba (byte-array (.remaining in))]
    (.get in ba)
    ba))

(defn- make-ws-client ^WebSocketClient
  [uri-str headers on-error on-message on-close]
  (let [uri (java.net.URI. uri-str)]
    (proxy [WebSocketClient] [^java.net.URI uri ^java.util.Map headers]
      (onOpen  [^org.java_websocket.handshake.ServerHandshake _handshake] nil)
      (onError [ex] (on-error ex))
      ;; Clojure's `proxy` uses one implementation for same-arity overloads.
      (onMessage [message]
        (on-message
          (if (instance? ByteBuffer message)
            (byte-buffer->ba message)
            message)))
      (onClose [code reason remote] (on-close code reason remote)))))

(defn make-client-ws
  "Returns nil or a delay that can be dereffed to get a connected Java
  `ClientWebSocket` backed by <https://github.com/TooTallNate/Java-WebSocket>."
  [{:keys [uri-str headers on-error on-message on-close]}]
  (when-let [^WebSocketClient ws-client
             (try
               (make-ws-client uri-str headers on-error on-message on-close)

               (catch Throwable t
                 (trove/log! {:level :error, :id :sente.client/create-java-ws-error, :error t, :data {:uri uri-str}})))]

    (delay
      (.connect ws-client)
      (reify
        i/IClientWebSocket
        (cws-raw   [_]                            ws-client)
        (cws-send  [_ data]               (.send  ws-client data))
        (cws-close [_ code reason clean?] (.close ws-client code reason))))))
