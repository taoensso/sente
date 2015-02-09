(ns taoensso.sente.interfaces
  "Experimental - subject to change!
  Public interfaces / extension points."
  #+clj  (:require [clojure.tools.reader.edn :as edn])
  #+cljs (:require [cljs.reader              :as edn]))

;;;; Network channels

#+clj
(defprotocol IAsyncNetworkChannel
  ;; Wraps a web server's own async channel/comms interface to abstract away
  ;; implementation differences
  (send!* [net-ch msg close-after-send?] "Sends a message to channel.")
  (open?  [net-ch] "Returns true iff the channel is currently open.")
  (close! [net-ch] "Closes the channel."))

#+clj (defn send! [net-ch msg & [close-after-send?]]
        (send!* net-ch msg close-after-send?))

#+clj
(defprotocol IAsyncNetworkChannelAdapter
  ;; Wraps a web server's own Ring-request->async-channel-response interface to
  ;; abstract away implementation differences
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    "Given a Ring request (WebSocket handshake or Ajax GET/POST), returns a Ring
    response map with a web-server-specific channel :body that implements
    Sente's IAsyncNetworkChannel protocol.

    Configures channel callbacks with a callbacks map using keys:
      :on-open  - (fn [net-ch]) called exactly once after channel is available
                  for sending.
      :on-close - (fn [net-ch status]) called exactly once after channel is
                  closed for ANY cause, incl. a call to `close!`.
      :on-msg   - (fn [net-ch msg]) called for each String or byte[] message
                  received from client. Currently only used for WebSocket clients."))

;;;; Packers

(defprotocol IPacker
  "Extension pt. for client<->server comms data un/packers:
  arbitrary Clojure data <-> serialized strings."
  (pack   [_ x])
  (unpack [_ x]))

(deftype EdnPacker []
  IPacker
  (pack   [_ x] (pr-str x))
  (unpack [_ s] (edn/read-string s)))

(def     edn-packer "Default Edn packer." (->EdnPacker))
(defn coerce-packer [x] (if (= x :edn) edn-packer
                          (do (assert (satisfies? IPacker x)) x)))
