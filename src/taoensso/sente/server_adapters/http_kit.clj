(ns taoensso.sente.server-adapters.http-kit
  "Sente server adapter for http-kit (http://www.http-kit.org/)."
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.sente.interfaces :as i]
   [org.httpkit.server :as hk]))

(extend-type org.httpkit.server.AsyncChannel
  i/IServerChan
  (sch-open?  [sch] (hk/open? sch))
  (sch-close! [sch] (hk/close sch))
  (sch-send!  [sch websocket? msg]
    (let [close-after-send? (if websocket? false true)]
      (hk/send! sch msg close-after-send?))))

(deftype HttpKitServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-close on-msg _on-error]} callbacks-map
          ws? (:websocket? ring-req)]

      ;; Returns {:body <http-kit-implementation-channel> ...}:
      (hk/with-channel ring-req sch
        (when on-close (hk/on-close   sch (fn [status-kw] (on-close sch ws? status-kw))))
        (when on-msg   (hk/on-receive sch (fn [msg]       (on-msg   sch ws? msg))))
        ;; http-kit channels are immediately open so don't have/need an
        ;; on-open callback. Do need to place this last though to avoid
        ;; racey side effects:
        (when on-open (on-open sch ws?))))))

(defn get-sch-adapter [] (HttpKitServerChanAdapter.))

(enc/deprecated
  (def http-kit-adapter "Deprecated" (get-sch-adapter))
  (def sente-web-server-adapter "Deprecated" http-kit-adapter))
