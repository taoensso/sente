(ns taoensso.sente.server-adapters.nginx-clojure
  "Sente server adapter for Nginx-Clojure v0.4.2+
  (http://nginx-clojure.github.io/)"
  {:author "Zhang Yuexiang (@xfeep)"}
  (:require [taoensso.sente.interfaces :as i]
            [nginx.clojure.core :as ncc]))

(def ^:dynamic *max-message-size*
  nginx.clojure.WholeMessageAdapter/DEFAULT_MAX_MESSAGE_SIZE)

(extend-type nginx.clojure.NginxHttpServerChannel
  i/IServerChan
  (sch-open?  [nc-ch] (not (ncc/closed? nc-ch)))
  (sch-close! [nc-ch] (ncc/close! nc-ch))
  (-sch-send! [nc-ch msg close-after-send?]
    (let [closed? (ncc/closed? nc-ch)]
      (ncc/send! nc-ch msg true (boolean close-after-send?))
      (not closed?))))

(deftype NginxServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [server-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map
          nc-ch (ncc/hijack! ring-req true)
          upgrade-ok? (ncc/websocket-upgrade! nc-ch false)]
      ;; Returns {:status 200 :body <nginx-clojure-implementation-channel>}:
      (when (not upgrade-ok?) ; Send normal header for non-websocket requests
        (.setIgnoreFilter nc-ch false)

        ;; For Sente #150, give client a chance to set broken listener.
        ;; We could do this via `send-header!` with something like
        ;; `(send-header! nc-ch 200, ..., true, false)`. Instead, we're
        ;; choosing this approach to match the behaviour of other adapters:
        (ncc/send! nc-ch nil true false)

        (ncc/send-header! nc-ch 200
          {"Content-Type" "text/html"} false false))

      (ncc/add-aggregated-listener! nc-ch *max-message-size*
        {:on-open    (when on-open  (fn [nc-ch]        (on-open  nc-ch)))
         :on-message (when on-msg   (fn [nc-ch msg]    (on-msg   nc-ch msg)))
         :on-close   (when on-close (fn [nc-ch reason] (on-close nc-ch reason)))
         :on-error   nil ; Do we need/want this?
         })
      {:status 200 :body nc-ch})))

(def nginx-clojure-adapter (NginxServerChanAdapter.))
(def sente-web-server-adapter
  "Alias for ns import convenience"
  nginx-clojure-adapter)
