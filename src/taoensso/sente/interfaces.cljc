(ns taoensso.sente.interfaces
  "Alpha, subject to change.
  Public interfaces / extension points."
  (:require [taoensso.encore :as enc]))

;;;; Server channels
;; To work with Sente, a web server needs to provide implementations for
;; the two protocols below. Please see the `taoensso.sente.server-adapters.*`
;; namespaces for examples.
;;
;; Ref. https://github.com/ptaoussanis/sente/issues/102 for more info.

(defprotocol IServerChan ; sch
  ;; Wraps a web server's own async channel/comms interface to abstract away
  ;; implementation differences.
  (sch-open?  [sch] "Returns true iff the channel is currently open.")
  (sch-close! [sch]
    "If the channel is open when called: closes the channel and returns true.
    Otherwise noops and returns false.")
  (sch-send! [sch websocket? msg]
    "If the channel is open when called: sends a message over channel and
    returns true. Otherwise noops and returns false."))

(defprotocol IServerChanAdapter ; sch-adapter
  ;; Wraps a web server's own ring-request->ring-response interface to
  ;; abstract away implementation differences.
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    "Given a Ring request (WebSocket handshake or Ajax GET/POST), returns
    a Ring response map with a web-server-specific channel :body that
    implements Sente's IServerChan protocol.

    Configures channel callbacks with a callbacks map using keys:
      :on-open  - (fn [server-ch websocket?]) called exactly once after
                  channel is available for sending.
      :on-close - (fn [server-ch websocket? status]) called exactly once
                  after channel is closed for any cause, incl. an explicit
                  call to `close!`. `status` type is currently undefined.
      :on-msg   - (fn [server-ch websocket? msg]) called for each String or
                  byte[] message received from client.
      :on-error - (fn [server-ch websocket? error]) currently unused."))

;;;; Packers

(defprotocol IPacker
  "Extension pt. for client<->server comms data un/packers:
  arbitrary Clojure data <-> serialized strings."
  (pack   [_ x])
  (unpack [_ x]))


;;;; Client sockets

(defprotocol IClientWebSocket ; cws
  ;; Wraps a client's socket interface to abstract away
  ;; implementation differences.
  
  (cws-close! [cws] "If the channel is open when called: closes the channel and returns true.
    Otherwise noops and returns false.")
  (cws-send! [cws msg]
   "If the socket is open when called: sends a message over socket and
    returns true. Otherwise noops and returns false."))


;; Client websocket creator can be provided in the field :cws-creator.
;; The result should implement IClientWebsocket.
;; Here are the arguments the creator should expect :
;; [url callbacks-map]
;; Where `callbacks-map` has the following entry:
;;      on-msg    - [msg] the message (ppstr)
;;      on-error  - [evt]
;;      on-close  - [evt]
;; The websocket creator should return nil if it can't connect.

