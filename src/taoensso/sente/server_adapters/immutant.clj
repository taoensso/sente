(ns taoensso.sente.server-adapters.immutant
  "Sente server adapter for Immutant v2+ (http://immutant.org/)"
  {:author "Toby Crawley (@tobias)"}
  (:require [taoensso.sente.interfaces :as i]
            [immutant.web.async :as immutant]))

(extend-type org.projectodd.wunderboss.web.async.Channel
  i/IServerChan
  (sch-open?  [im-ch] (immutant/open? im-ch))
  (sch-close! [im-ch] (immutant/close im-ch))
  (-sch-send! [im-ch msg close-after-send?]
    (immutant/send! im-ch msg {:close? close-after-send?})))

(deftype ImmutantServerChanAdapter
    [lp-timeout-ms ; Nb Ref. https://goo.gl/t4RolO
     ]
  i/IServerChanAdapter
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map]
      ;; Returns {:status 200 :body <immutant-implementation-channel>}:
      (immutant/as-channel ring-req
        :on-open     (when on-open (fn [im-ch] (on-open im-ch)))
        ;; :on-error (fn [im-ch throwable]) ; Do we need/want this?
        :on-close    (when on-close
                       (fn [im-ch {:keys [code reason] :as status-map}]
                         (on-close im-ch status-map)))
        :on-message  (when on-msg (fn [im-ch message] (on-msg im-ch message)))
        :timeout     (if (:websocket? ring-req) 0 lp-timeout-ms)))))

(defn make-immutant-adapter
  "Returns a new Immutant adapter. Useful for overriding the default
  :lp-timeout-ms option that specifies server-side timeout for
  Ajax (long-polling) connections.

  NB: if you override the :lp-timeout-ms option in your client-side call
  to `make-channel-socket!`, you'll need to provide that same value here.

  If you aren't customizing the client-side :lp-timeout-ms, you
  can safely use the default Immutant adapter (`immutant-adapter` or
  `sente-web-server-adapter`)."

  [{:keys [lp-timeout-ms]
    :or   {lp-timeout-ms 25000}}]

  (ImmutantServerChanAdapter. lp-timeout-ms))

(def immutant-adapter (make-immutant-adapter nil))
(def sente-web-server-adapter
  "Alias for ns import convenience"
  immutant-adapter)
