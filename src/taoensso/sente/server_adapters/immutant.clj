(ns taoensso.sente.server-adapters.immutant
  "Experimental- subject to change!
  Optional Immutant v2+ adapter for use with Sente."
  (:require [taoensso.sente.interfaces :as i]
            [immutant.web.async :as immutant]))

(extend-type org.projectodd.wunderboss.web.async.Channel
  i/IAsyncNetworkChannel
  (open?  [im-ch] (immutant/open? im-ch))
  (close! [im-ch] (immutant/close im-ch))
  (send!* [im-ch msg close-after-send?]
    (immutant/send! im-ch msg {:close? close-after-send?})))

(deftype ImmutantAsyncNetworkChannelAdapter [lp-timeout-ms]
  i/IAsyncNetworkChannelAdapter
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map]
      ;; Returns {:status 200 :body <immutant-implementation-channel>}:
      (immutant/as-channel ring-req
        :on-open     (when on-open (fn [im-ch] (on-open im-ch)))
        ;; :on-error (fn [im-ch throwable]) ; TODO Do we need/want this?
        :on-close    (when on-close
                       (fn [im-ch {:keys [code reason] :as status-map}]
                         (on-close im-ch status-map)))
        :on-message  (when on-msg (fn [im-ch message] (on-msg im-ch message)))
        :timeout     (if (:websocket? ring-req) 0 lp-timeout-ms)))))

(defn make-immutant-adapter
  "Creates an Immutant adapter.

  Allows you to override the :lp-timeout-ms option, which specifies
  server-side timeout for long-polling connections. If you override the
  :lp-timeout-ms option in yourclient-side call to
  `make-channel-socket!`, you'll need to provide that same value
  here. If you aren't customizing the client-side :lp-timeout-ms, you
  can safely use the default Immutant adapter (`immutant-adapter` or
  `sente-web-server-adapter`). "
  [{:keys [lp-timeout-ms]
    :or   {lp-timeout-ms 25000}}]
  (ImmutantAsyncNetworkChannelAdapter. lp-timeout-ms))

(def immutant-adapter (make-immutant-adapter nil))
(def sente-web-server-adapter immutant-adapter) ; Alias for ns import convenience
