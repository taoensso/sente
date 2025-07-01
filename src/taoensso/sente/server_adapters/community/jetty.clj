(ns taoensso.sente.server-adapters.community.jetty
  {:author "Alex Gunnarson (@alexandergunnarson)"}
  (:require
    [ring.core.protocols       :as ring-protocols]
    [ring.util.response        :as ring-response]
    [ring.websocket            :as ws]
    [ring.websocket.protocols  :as ws.protocols]
    [taoensso.encore           :as enc]
    [taoensso.sente.interfaces :as i]
    [taoensso.trove :as trove])

  (:import
   [ring.websocket.protocols Socket]))

;;;; WebSockets

(enc/compile-if org.eclipse.jetty.websocket.common.WebSocketSession
  (extend-type  org.eclipse.jetty.websocket.common.WebSocketSession ; Jetty 12
    i/IServerChan
    (sch-open?  [sch] (.isOpen sch))
    (sch-close! [sch] (.close  sch))
    (sch-send!  [sch-listener _websocket? msg]
      (ws.protocols/-send sch-listener msg)
      true)))

(enc/compile-if org.eclipse.jetty.websocket.api.WebSocketListener
  (extend-type  org.eclipse.jetty.websocket.api.WebSocketListener ; Jetty 11
    i/IServerChan
    (sch-open?  [sch] (.isOpen sch))
    (sch-close! [sch] (.close  sch))
    (sch-send!  [sch-listener _websocket? msg]
      (ws.protocols/-send sch-listener msg)
      true)))

(extend-type Socket
  i/IServerChan
    (sch-open?  [sch] (ws.protocols/-open? sch))
    (sch-close! [sch] (ws.protocols/-close sch nil nil))
    (sch-send!  [sch-socket _websocket? msg]
      (ws.protocols/-send sch-socket msg)
      true))

(defn- respond-ws
  [{:keys [websocket-subprotocols]}
   {:keys [on-close on-error on-msg on-open]}]

  {:ring.websocket/protocol (first websocket-subprotocols)
   :ring.websocket/listener
   (reify ws.protocols/Listener
     (on-close   [_ sch status _] (on-close sch true status))
     (on-error   [_ sch error]    (on-error sch true error))
     (on-message [_ sch msg]      (on-msg   sch true msg))
     (on-open    [_ sch]          (on-open  sch true))
     (on-pong    [_ sch data]))})

;;;; Ajax

(defprotocol ISenteJettyAjaxChannel
  (ajax-read! [sch]))

(deftype SenteJettyAjaxChannel [ring-req resp-promise_ open?_ on-close adapter-opts]
  i/IServerChan
  (sch-send!  [sch _websocket? msg] (deliver resp-promise_ msg) (i/sch-close! sch))
  (sch-open?  [sch] @open?_)
  (sch-close! [sch]
    (when (compare-and-set! open?_ true false)
      (deliver resp-promise_ nil)
      (when on-close (on-close sch false nil))
      true))

  ISenteJettyAjaxChannel
  (ajax-read! [_sch]
    (let [{:keys [ajax-resp-timeout-ms]} adapter-opts
          resp
          (if ajax-resp-timeout-ms
            (deref resp-promise_ ajax-resp-timeout-ms ::timeout)
            (deref resp-promise_))]

      (if (= resp ::timeout)
        (throw (ex-info "Ajax read timeout" {:timeout-msecs ajax-resp-timeout-ms}))
        resp))))

(defn- ajax-ch
  [ring-req {:keys [on-open on-close]} adapter-opts]
  (let [open?_ (atom true)
        sch    (SenteJettyAjaxChannel. ring-req (promise) open?_ on-close adapter-opts)]
    (when on-open (on-open sch false))
    sch))

(extend-protocol ring-protocols/StreamableResponseBody
  SenteJettyAjaxChannel
  (write-body-to-stream [body _response ^java.io.OutputStream output-stream]
    ;; Use `future` because `output-stream` might block the thread,
    ;; Ref. <https://github.com/ring-clojure/ring/issues/254#issuecomment-236048380>
    (future
      (try
        (.write output-stream (.getBytes ^String (ajax-read! body) java.nio.charset.StandardCharsets/UTF_8))
        (.flush output-stream)
        (catch Throwable t
          (trove/log! {:level :error, :id :sente.server.jetty/write-body-to-stream-error, :error t}))
        (finally
          (.close output-stream))))))

;;;; Adapter

(deftype JettyServerChanAdapter [adapter-opts]
  i/IServerChanAdapter
  (ring-req->server-ch-resp [_ ring-req callbacks-map]
    (if (ws/upgrade-request? ring-req)
      (do                     (respond-ws ring-req callbacks-map))
      (ring-response/response (ajax-ch    ring-req callbacks-map adapter-opts)))))

(defn get-sch-adapter
  "Returns a Sente `ServerChan` adapter for `ring-jetty-adapter` [1].
  Supports Jetty 11, 12.

  Options:
     `:ajax-resp-timeout-ms` - Max msecs to wait for Ajax responses (default 60 secs),
                               exception thrown on timeout.

  [1] Ref. <https://github.com/ring-clojure/ring/tree/master/ring-jetty-adapter>."
  ([] (get-sch-adapter nil))
  ([{:as   opts
     :keys [ajax-resp-timeout-ms]
     :or   {ajax-resp-timeout-ms (* 60 1000)}}]

   (JettyServerChanAdapter.
     (assoc opts
       :ajax-resp-timeout-ms ajax-resp-timeout-ms))))
