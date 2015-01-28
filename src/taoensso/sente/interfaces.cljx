(ns taoensso.sente.interfaces
  "Experimental (pre-alpha): subject to change.
  Public interfaces / extension points."
  #+clj  (:require [clojure.tools.reader.edn :as edn])
  #+cljs (:require [cljs.reader              :as edn]))

;;;; Channels

#+clj
(defprotocol IAsyncNetworkChannel
  (send! [ch msg] [ch msg close?]
    "Sends a message to the channel. `close?` defaults to false.")
  (open? [ch]
    "Is the channel currently open?")
  (close [ch]
    "Close the channel."))

#+clj
(defn as-channel
  "Converts the current ring `request` in to an asynchronous channel.

  The callbacks common to both channel types are:

  * :on-open - `(fn [ch] ...)` - called when the channel is
    available for sending.
  * :on-close - `(fn [ch status] ...)` - called for *any* close,
    including a call to [[close]], but will only be invoked once.
    `ch` will already be closed by the time this is invoked.

  If the channel is a Websocket, the following callback is also used:

  * :on-receive - `(fn [ch message] ...)` - Called for each message
    from the client. `message` will be a `String` or `byte[]`"
  [request & {:keys [on-open on-close on-receive]}]
  (throw (IllegalStateException. "No implementation of as-channel provided")))

#+clj
(defn provide-as-channel!
  "Sets as-channel to f."
  [f]
  (alter-var-root #'as-channel (constantly f)))

#+clj
(defn websocket?
  "Is the request a websocket request?"
  [request]
  (:websocket? request))

#+clj
(defmacro when-import
  "Convenience macro to conditionally eval code based on the availability of an import."
  [spec pre-body & body]
  `(when (try
           (import ~spec)
           (catch ClassNotFoundException _#))
     ~pre-body
     (eval '(do ~@body))))

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
