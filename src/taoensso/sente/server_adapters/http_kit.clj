(ns taoensso.sente.server-adapters.http-kit
  "Sente server adapter for http-kit,
  Ref. <https://github.com/http-kit/http-kit>."
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

      ;; Note `as-channel` requires http-kit >= v2.4.0
      ;; Returns {:body <http-kit-implementation-channel> ...}:
      (hk/as-channel ring-req
        {:on-close   (when on-close (fn [sch status-kw] (on-close sch ws? status-kw)))
         :on-receive (when on-msg   (fn [sch       msg] (on-msg   sch ws? msg)))
         :on-open    (when on-open  (fn [sch          ] (on-open  sch ws?)))}))))

(defn get-sch-adapter [] (HttpKitServerChanAdapter.))

(enc/deprecated
  (def ^:deprecated ^:no-doc http-kit-adapter (get-sch-adapter))
  (def ^:deprecated ^:no-doc sente-web-server-adapter http-kit-adapter))
