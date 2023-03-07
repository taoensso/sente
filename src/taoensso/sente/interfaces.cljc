(ns taoensso.sente.interfaces
  "Alpha, subject to change.
  Public interfaces / extension points.
  Ref. https://github.com/ptaoussanis/sente/issues/425 for more info."
  (:require [taoensso.encore :as enc]))

;;;; Web servers

(defprotocol IServerChanAdapter ; sch-adapter
  "For Sente to support a web server, an \"adapter\" for that server
  must be provided that implements this protocol."

  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    "Given a Ring request (WebSocket GET handshake or Ajax GET/POST),
    returns a Ring response map appropriate for the underlying web server.

    `callbacks-map` contains the following functions that MUST be called as described:

      `:on-open` - (fn [sch websocket?])
        Call exactly once after `sch` is available for sending.

      `:on-close` - (fn [sch websocket? status])
        Call exactly once after `sch` is closed for any cause, incl. an
        explicit call to `sch-close!`. `status` arg type is currently undefined.

      `:on-msg` - (fn [sch websocket? msg])
        Call for each `String` or byte[] message received from client.

      `:on-error` - (fn [sch websocket? error])
        Currently unused.

       Note: all `sch` (\"server channel\") args provided above MUST implement
       the `IServerChan` protocol.

    `callbacks-map` contains the following functions IFF server is configured to
    use 3-arity (async) Ring v1.6+ handlers:

      `:ring-async-resp-fn`  - ?(fn [ring-response])
      `:ring-async-raise-fn` - ?(fn [throwable])"))

(defprotocol IServerChan ; sch
  "This protocol must be implemented by the \"server channel\" arguments
  provided to callback functions via `ring-req->server-ch-resp`."

  (sch-open?  [sch] "Returns true iff the channel is currently open.")
  (sch-close! [sch]
    "If the channel is open when called: closes the channel and returns true.
    Otherwise noops and returns falsey.")
  (sch-send! [sch websocket? msg]
    "If the channel is open when called: sends a message over channel and
    returns true. Otherwise noops and returns falsey."))

;;;; Packers

(defprotocol IPacker
  "Extension pt. for client<->server comms data un/packers:
  arbitrary Clojure data <-> serialized payloads.

  NB if dealing with non-string payloads, see also
  `taoensso.sente/*write-legacy-pack-format?*`."

  (pack   [_ x])
  (unpack [_ x]))
