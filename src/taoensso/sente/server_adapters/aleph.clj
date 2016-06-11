(ns taoensso.sente.server-adapters.aleph
  (:require
    [taoensso.sente.interfaces :as i]
    [aleph.http :as http]
    [manifold.stream :as s]
    [manifold.deferred :as d]))

(extend-type manifold.stream.core.IEventSink
  i/IServerChan
  (sch-open? [s] (not (s/closed? s)))
  (sch-close! [s] (s/close! s))
  (-sch-send! [s msg close-after-send?]
    (s/put! s msg)
    (when close-after-send?
      (s/close! s))))

(defn websocket-request? [req]
  (= "websocket" (-> req :headers (get "upgrade"))))

(deftype AlephAsyncNetworkChannelAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map]
      (if (websocket-request? ring-req)
        (d/chain (http/websocket-connection ring-req)
          (fn [s]
            (when on-open (on-open s))
            (when on-msg (s/consume on-msg s))
            (when on-close (s/on-closed s #(on-close s nil)))
            {:body s}))
        (let [s (s/stream)]
          (when on-open (on-open s))
          (when on-close (s/on-closed s #(on-close s nil)))
          {:body s})))))

(def aleph-adapter (AlephAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter aleph-adapter) ; Alias for ns import convenience
