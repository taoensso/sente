(ns taoensso.sente.server-adapters.immutant
  "Sente server adapter for Immutant v2+ (http://immutant.org/)."
  {:author "Toby Crawley (@tobias)"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.sente.interfaces :as i]
   [immutant.web.async :as imm]))

(extend-type org.projectodd.wunderboss.web.async.Channel
  i/IServerChan
  (sch-open?  [sch] (imm/open? sch))
  (sch-close! [sch] (imm/close sch))
  (sch-send!  [sch websocket? msg]
    (let [close-after-send? (if websocket? false true)]
      (imm/send! sch msg {:close? close-after-send?}))))

(deftype ImmutantServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-close on-msg on-error]} callbacks-map
          ws? (:websocket? ring-req)]

      ;; Returns {:body <immutant-implementation-channel> ...}:
      (imm/as-channel ring-req
        :timeout     0 ; Deprecated, Ref. https://goo.gl/t4RolO
        :on-open     (when on-open  (fn [sch          ] (on-open  sch ws?)))
        :on-error    (when on-error (fn [sch throwable] (on-error sch ws? throwable)))
        :on-message  (when on-msg   (fn [sch msg      ] (on-msg   sch ws? msg)))
        :on-close    (when on-close
                       (fn [sch {:keys [code reason] :as status-map}]
                         (on-close sch ws? status-map)))))))

(defn get-sch-adapter [] (ImmutantServerChanAdapter.))

(enc/deprecated
  (defn make-immutant-adapter "Deprecated" [_opts] (get-sch-adapter))
  (def immutant-adapter "Deprecated" (get-sch-adapter))
  (def sente-web-server-adapter immutant-adapter))
