(ns taoensso.sente.server-adapters.nginx-clojure
  "Experimental- subject to change!
  Optional Nginx-Clojure v0.4.2+ adapter for use with Sente."
  (:require [taoensso.sente.interfaces :as i]
            [nginx.clojure.core :as ncc]))

(def ^:dynamic *max-message-size* nginx.clojure.WholeMessageAdapter/DEFAULT_MAX_MESSAGE_SIZE)

(extend-type nginx.clojure.NginxHttpServerChannel
  i/IAsyncNetworkChannel
  (open?  [nc-ch] (not (ncc/closed? nc-ch)))
  (close! [nc-ch] (ncc/close! nc-ch))
  (send!* [nc-ch msg close-after-send?]
    (let [closed? (ncc/closed? nc-ch)]
      (ncc/send! nc-ch msg true (boolean close-after-send?))
      (not closed?))))

(deftype NginxClojureAsyncNetworkChannelAdapter []
  i/IAsyncNetworkChannelAdapter
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    (let [{:keys [on-open on-msg on-close]} callbacks-map
          nc-ch (ncc/hijack! ring-req true)
          upgrade-ok? (ncc/websocket-upgrade! nc-ch false)]
      ;; Returns {:status 200 :body <nginx-clojure-implementation-channel>}:
      (when (not upgrade-ok?) ;; send general header for non-websocket request
        (.setIgnoreFilter nc-ch false)
        (ncc/send-header! nc-ch 200  {"Content-Type" "text/html"} false false))
      (ncc/add-aggregated-listener! nc-ch *max-message-size*
        {:on-open (when on-open (fn [nc-ch] (on-open nc-ch)))
         :on-error nil ;;Do we need/want this?
         :on-message (when on-msg (fn [nc-ch msg] (on-msg nc-ch msg)))
         :on-close (when on-close (fn [nc-ch reason] (on-close nc-ch reason)))})
      {:status 200 :body nc-ch})))

(def nginx-clojure-adapter (NginxClojureAsyncNetworkChannelAdapter.))
(def sente-web-server-adapter nginx-clojure-adapter) ; Alias for ns import convenience
