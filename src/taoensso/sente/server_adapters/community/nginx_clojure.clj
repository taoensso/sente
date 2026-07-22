(ns ^:no-doc taoensso.sente.server-adapters.community.nginx-clojure
  ;; `^:no-doc` needed to prevent broken cljdoc build
  {:author "Zhang Yuexiang (@xfeep)"}
  (:require
   [clojure.string :as str]
   [taoensso.encore :as enc]
   [taoensso.sente.interfaces :as i]
   [nginx.clojure.core :as ncc])

  (:import
   [nginx.clojure NginxRequest NginxSimpleHandler]))

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
    (let [{:keys [on-open on-close on-msg _on-error ws-protocol]} callbacks-map
          ^NginxRequest native-req ring-req
          sch (ncc/hijack! ring-req true)
          ws-request?
          (= "websocket"
            (some-> (get-in ring-req [:headers "upgrade"]) str/lower-case))

          ^java.util.Map protocol-headers
          (when (and ws-request? ws-protocol)
            (java.util.Collections/singletonMap
              "Sec-WebSocket-Protocol" ws-protocol))

          _
          (when protocol-headers
            ;; Native upgrade generates the rest of the 101 response after this.
            (.prepareHeaders ^NginxSimpleHandler (.handler native-req)
              native-req 101 (.entrySet protocol-headers)))

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

(defn get-sch-adapter
    "Returns a Sente `ServerChan` adapter for `Nginx-Clojure` v0.4.2+.
  Ref. <https://github.com/nginx-clojure/nginx-clojure>."
  [] (NginxServerChanAdapter.))
