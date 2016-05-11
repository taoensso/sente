(ns taoensso.sente.server-adapters.aleph
  "Sente server adapter for Aleph."
  {:author "Zach Tellman <@ztellman>, Alex Gunnarson <@alexandergunnarson>"}
  (:require
    [taoensso.sente.interfaces :as i   ]
    [aleph.http                :as http]
    [manifold.stream           :as s   ]
    [manifold.deferred         :as d   ]))

(extend-type manifold.stream.core.IEventSink
  i/IServerChan
  (sch-open?  [hk-ch] (not (s/closed? hk-ch)))
  (sch-close! [hk-ch] (s/close! hk-ch))
  (-sch-send! [hk-ch msg close-after-send?]
    (s/put! hk-ch msg)
    (when close-after-send?
      #_(s/close! hk-ch)))) ; This causes problems because it seems that it wants to close the socket *every* time even with a callback

(defn websocket-request? [req]
  (= "websocket" (-> req :headers (get "upgrade"))))

(deftype AlephAsyncNetworkChannelAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [this req callbacks]
    (let [{:keys [on-open on-msg on-close]} callbacks]
      (if (websocket-request? req)
          (d/chain (http/websocket-connection req)
            (fn [s]
              (when on-open  (on-open s))
              (when on-msg   (s/consume (fn [result] (on-msg s result)) s))
              (when on-close (s/on-closed s #(on-close s nil)))
              {:body s}))
          (let [s (s/stream)]
            (when on-open  (on-open s))
            (when on-close (s/on-closed s #(on-close s nil)))
            {:body s})))))

(def aleph-adapter (AlephAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter aleph-adapter) ; Alias for ns import convenience