(ns taoensso.sente.servers.http-kit
  "Experimental (pre-alpha): subject to change.
  Optional http-kit[1] Channel implementation for use with Sente.
  [1] http://http-kit.org/."
  (:require [taoensso.sente.interfaces :as i]))

(i/when-import org.httpkit.server.AsyncChannel
  (require '[org.httpkit.server :as http-kit])

  (extend-type AsyncChannel
    i/IAsyncNetworkChannel
    (open? [ch] (http-kit/open? ch))
    (close [ch] (http-kit/close ch))
    (send!
      ([ch msg]        (i/send! ch msg false))
      ([ch msg close?] (http-kit/send! ch msg close?))))

  (i/provide-as-channel!
    (fn[request & {:keys [on-open on-close on-receive]}]
      (http-kit/with-channel request ch
        (when (and (i/websocket? request)
                on-receive)
          (http-kit/on-receive ch (partial on-receive ch)))
        (when on-close
          (http-kit/on-close ch (partial on-close ch)))
        (when on-open
          ;; in http-kit, the channel is already open, so notify immediately
          (on-open ch))))))
