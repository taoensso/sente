(ns taoensso.sente.server-adapters.nginx-clojure
  "Sente server adapter for Nginx-Clojure v0.4.2+
  (http://nginx-clojure.github.io/)."
  {:author "Zhang Yuexiang (@xfeep)"}
  (:require
   [taoensso.encore :as enc]
   [taoensso.sente.interfaces :as i]
   [nginx.clojure.core :as ncc]))

(def ^:dynamic *max-message-size*
  nginx.clojure.WholeMessageAdapter/DEFAULT_MAX_MESSAGE_SIZE)

(extend-type nginx.clojure.NginxHttpServerChannel
  i/IServerChan
  (sch-open?  [sch] (not (ncc/closed? sch)))
  (sch-close! [sch]       (ncc/close! sch))
  (sch-send!  [sch websocket? msg]
    (if (ncc/closed? sch)
      false
      (let [close-after-send? (if websocket? false true)]
        (ncc/send! sch msg true (boolean close-after-send?))
        true))))

(deftype NginxServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [sch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-close on-msg _on-error]} callbacks-map
          sch (ncc/hijack! ring-req true)
          ws? (ncc/websocket-upgrade! sch false)]

      ;; Returns {:status 200 :body <nginx-clojure-implementation-channel>}:
      (when-not ws? ; Send normal header for non-websocket requests
        (.setIgnoreFilter sch false)

        ;; For Sente #150, give client a chance to set broken listener.
        ;; We could do this via `send-header!` with something like
        ;; `(send-header! sch 200, ..., true, false)`. Instead, we're
        ;; choosing this approach to match the behaviour of other adapters:
        (ncc/send! sch nil true false)
        (ncc/send-header! sch 200 {"Content-Type" "text/html"} false false))

      (ncc/add-aggregated-listener! sch *max-message-size*
        {:on-open    (when on-open  (fn [sch]        (on-open  sch ws?)))
         :on-message (when on-msg   (fn [sch msg]    (on-msg   sch ws? msg)))
         :on-close   (when on-close (fn [sch reason] (on-close sch ws? reason)))
         :on-error   nil})

      {:status 200 :body sch})))

(defn get-sch-adapter [] (NginxServerChanAdapter.))

(enc/deprecated
  (def nginx-clojure-adapter (get-sch-adapter))
  (def sente-web-server-adapter nginx-clojure-adapter))
