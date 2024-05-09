(ns taoensso.sente.server-adapters.jetty
  "Sente adapter for `ring-jetty-adapter`
   (https://github.com/ring-clojure/ring/tree/master/ring-jetty-adapter).

   Adapted from https://github.com/taoensso/sente/pull/426#issuecomment-1647231979."
  (:require
    [ring.core.protocols :as ring-protocols]
    [ring.util.response :as ring-response]
    [ring.websocket :as ws]
    [ring.websocket.protocols :as ws.protocols]
    [taoensso.sente.interfaces :as i]
    [taoensso.timbre :as log])
  (:import
    [org.eclipse.jetty.websocket.api WebSocketListener]
    [ring.websocket.protocols Socket]))

;; ===== WebSocket ===== ;;

(extend-type WebSocketListener
  i/IServerChan
    (sch-open? [sch] (.isOpen sch))
    (sch-close! [sch] (.close sch))
    (sch-send! [sch-listener _websocket? msg]
      (ws.protocols/-send sch-listener msg)
      true))

(extend-type Socket
  i/IServerChan
    (sch-open? [sch] (ws.protocols/-open? sch))
    (sch-close! [sch] (ws.protocols/-close sch nil nil))
    (sch-send! [sch-socket _websocket? msg]
      (ws.protocols/-send sch-socket msg)
      true))

(defn- respond-ws
  [{:keys [websocket-subprotocols]}
   {:keys [on-close on-error on-msg on-open]}]
  {:ring.websocket/listener (reify
                              ws.protocols/Listener
                                (on-close [_ sch status _] (on-close sch true status))
                                (on-error [_ sch error] (on-error sch true error))
                                (on-message [_ sch msg] (on-msg sch true msg))
                                (on-open [_ sch] (on-open sch true))
                                (on-pong [_ sch data]))
   :ring.websocket/protocol (first websocket-subprotocols)})

;; ===== AJAX ===== ;;

(defprotocol ISenteJettyAjaxChannel
  (ajax-read! [sch]))

(deftype SenteJettyAjaxChannel [resp-promise_ open?_ on-close adapter-opts]
  i/IServerChan
    (sch-send! [sch _websocket? msg]
      (deliver resp-promise_ msg)
      (i/sch-close! sch))
    (sch-open? [_sch]
      @open?_)
    (sch-close! [sch]
      (when (compare-and-set! open?_ true false)
        (deliver resp-promise_ nil)
        (when on-close
          (on-close sch false nil))
        true))

  ISenteJettyAjaxChannel
    (ajax-read! [_sch]
      (let [{:keys [ajax-resp-timeout-ms ajax-resp-timeout-val]} adapter-opts]
        (if ajax-resp-timeout-ms
            (deref resp-promise_ ajax-resp-timeout-ms ajax-resp-timeout-val)
            (deref resp-promise_)))))

(defn- ajax-ch
  [{:keys [on-open on-close]} adapter-opts]
  (let [open?_ (atom true)
        sch    (SenteJettyAjaxChannel. (promise) open?_ on-close adapter-opts)]
    (when on-open
      (on-open sch false))
    sch))

(extend-protocol ring-protocols/StreamableResponseBody
  SenteJettyAjaxChannel
    (write-body-to-stream [body _response ^java.io.OutputStream output-stream]
      ;; NOTE We could consider wrapping `try` with e.g. `future`, because `output-stream` might
      ;; block the thread (https://github.com/ring-clojure/ring/issues/254#issuecomment-236048380)
      (future
        (try
          (.write output-stream (.getBytes ^String (ajax-read! body) "UTF-8"))
          (.flush output-stream)
          (catch Throwable ex
            (log/error ex))
          (finally
            (.close output-stream))))))

;; ===== Overall ===== ;;

(deftype JettyServerChanAdapter [adapter-opts]
  i/IServerChanAdapter
    (ring-req->server-ch-resp [_ request callbacks-map]
      (if (ws/upgrade-request? request)
          (respond-ws request callbacks-map)
          (ring-response/response (ajax-ch callbacks-map adapter-opts)))))

(defn get-sch-adapter
  "Returns an Jetty ServerChanAdapter. Options:
     :ajax-resp-timeout-ms  ; Max msecs to wait for Ajax responses (default 60 secs)
     :ajax-resp-timeout-val ; Value returned in case of above timeout
                            ; (default `:ajax-resp-timeout`)"
  ([] (get-sch-adapter nil))
  ([{:as   opts
     :keys [ajax-resp-timeout-ms
            ajax-resp-timeout-val]
     :or   {ajax-resp-timeout-ms  (* 60 1000)
            ajax-resp-timeout-val :ajax-resp-timeout}}]
   (JettyServerChanAdapter.
     (assoc opts
       :ajax-resp-timeout-ms  ajax-resp-timeout-ms
       :ajax-resp-timeout-val ajax-resp-timeout-val))))
