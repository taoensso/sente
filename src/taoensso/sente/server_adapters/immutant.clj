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

(deftype ImmutantServerChanAdapter []
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
        :timeout     0 ; Deprecated, Ref. https://goo.gl/t4RolO
        ))))

(defn make-immutant-adapter [_opts] (ImmutantServerChanAdapter.))

(def immutant-adapter (make-immutant-adapter nil))
(def sente-web-server-adapter
  "Alias for ns import convenience"
  immutant-adapter)
