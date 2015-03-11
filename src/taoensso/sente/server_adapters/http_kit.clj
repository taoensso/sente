(ns taoensso.sente.server-adapters.http-kit
  "Experimental- subject to change!
  Optional http-kit adapter for use with Sente."
  (:require [taoensso.sente.interfaces :as i]
            [org.httpkit.server :as http-kit]))

(extend-type org.httpkit.server.AsyncChannel
  i/IAsyncNetworkChannel
  (open?  [hk-ch] (http-kit/open? hk-ch))
  (close! [hk-ch] (http-kit/close hk-ch))
  (send!* [hk-ch msg close-after-send?]
    (http-kit/send! hk-ch msg close-after-send?)))

(deftype HttpKitAsyncNetworkChannelAdapter []
  i/IAsyncNetworkChannelAdapter
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map]
      ;; Returns {:body <http-kit-implementation-channel>}:
      (http-kit/with-channel ring-req hk-ch

        (when on-close
          (http-kit/on-close hk-ch
            (fn [status-keyword] (on-close hk-ch status-keyword))))

        (when (and on-msg (:websocket? ring-req))
          (http-kit/on-receive hk-ch (fn [msg] (on-msg hk-ch msg))))

        ;; http-kit channels are immediately open so don't have/need an
        ;; on-open callback:
        (when on-open (on-open hk-ch)) ; Place last (racey side effects)
        ))))

(def http-kit-adapter (HttpKitAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter http-kit-adapter) ; Alias for ns import convenience
