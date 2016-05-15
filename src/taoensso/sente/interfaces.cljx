(ns taoensso.sente.interfaces
  "Alpha, subject to change.
  Public interfaces / extension points."
  (:require [taoensso.encore :as enc]))

;;;; Server channels

;; For use with Sente, a web server must implement the 2 protocols here.
;;
;; Some assumptions made by Sente:
;; - Server will set `{:websocket? true}` in the Ring request map of
;;   WebSocket handshakes.
;; - If the client closes a connection, the server will detect and close
;;   as well, without any interaction from Sente itself.
;;
;; Please see the `taoensso.sente.server-adapters.*` namespaces for
;; examples. Ref. https://github.com/ptaoussanis/sente/issues/102 for more
;; info and/or questions.

(defprotocol IServerChan
  ;; Wraps a web server's own async channel/comms interface to abstract away
  ;; implementation differences
  (sch-open?  [server-ch] "Returns true iff the server channel is currently open")
  (sch-close! [server-ch]
    "Closes the server channel and returns true iff the channel was open when
    called. Noops if the server channel is already closed.")
  (-sch-send! [server-ch msg close-after-send?]
    "Sends a message to server channel. Returns true iff the channel was open
    when called."))

(defn sch-send!
  "Sends a message to server channel. Returns true iff the channel was open
   when called."
  ([server-ch msg                  ] (-sch-send! server-ch msg false))
  ([server-ch msg close-after-send?] (-sch-send! server-ch msg close-after-send?)))

(defprotocol IServerChanAdapter
  ;; Wraps a web server's own Ring-request->async-channel-response interface to
  ;; abstract away implementation differences
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    "Given a Ring request (WebSocket handshake or Ajax GET/POST), returns a Ring
    response map with a web-server-specific channel :body that implements
    Sente's IServerChan protocol.

    Configures channel callbacks with a callbacks map using keys:
      :on-open  - (fn [server-ch]) called exactly once after channel is
                  available for sending.
      :on-close - (fn [server-ch status]) called exactly once after channel is
                  closed for ANY cause, incl. a call to `close!`.
      :on-msg   - (fn [server-ch msg]) called for each String or byte[] message
                  received from client. Currently only used for WebSocket clients."))

;;;; Packers

(defprotocol IPacker
  "Extension pt. for client<->server comms data un/packers:
  arbitrary Clojure data <-> serialized strings."
  (pack   [_ x])
  (unpack [_ x]))
