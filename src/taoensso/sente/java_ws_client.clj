(ns taoensso.sente.java-ws-client
  (:require
   [taoensso.timbre :as timbre]
   [taoensso.sente.interfaces :as i])

  (:import [org.java_websocket.client WebSocketClient]))

(defn make-client-ws
  "Returns nil or a delay that can be dereffed to get a connected Java
  `ClientWebSocket` backed by <https://github.com/TooTallNate/Java-WebSocket>."
  [{:keys [uri-str headers on-error on-message on-close]}]
  (when-let [^WebSocketClient ws-client
             (try
               (let [uri (java.net.URI. uri-str)
                     #_headers
                     #_
                     (ImmutableMap/of
                       "Origin"                   "http://localhost:3200"
                       "Referer"                  "http://localhost:3200"
                       "Sec-WebSocket-Extensions" "permessage-deflate; client_max_window_bits")]

                 (proxy [WebSocketClient] [^java.net.URI uri ^java.util.Map headers]
                   (onOpen    [^org.java_websocket.handshake.ServerHandshake handshakedata] nil)
                   (onError   [ex]                 (on-error   ex))
                   (onMessage [^String message]    (on-message message))
                   (onClose   [code reason remote] (on-close   code reason remote))))

               (catch Throwable t
                 (timbre/errorf t "Error creating Java WebSocket client")
                 nil))]

    (delay
      (.connect ws-client)
      (reify
        i/IClientWebSocket
        (cws-raw   [_]                            ws-client)
        (cws-send  [_ data]               (.send  ws-client data))
        (cws-close [_ code reason clean?] (.close ws-client code reason clean?))))))
