(ns taoensso.sente.server-adapters.community.jetty9
  "Sente adapter for ring-jetty9-adapter,
  (https://github.com/sunng87/ring-jetty9-adapter).

  Note that ring-jetty9-adapter defines WebSocket routes/handlers
  separately from regular Ring routes/handlers [1,2].

  This can make it tricky to set up stateful middleware correctly
  (for example as you may want to do for CSRF protection).

  See [3] for a full example.

  [1] https://github.com/sunng87/ring-jetty9-adapter/blob/master/examples/rj9a/websocket.clj
  [2] https://github.com/sunng87/ring-jetty9-adapter/issues/41#issuecomment-630206233
  [3] https://gist.github.com/wavejumper/40c4cbb21d67e4415e20685710b68ea0"

  {:author "Thomas Crowley (@wavejumper), modified for async jetty by Timo Kramer"}
  (:require [ring.adapter.jetty9.websocket :as jetty9.websocket]
            [taoensso.sente.interfaces :as i]))

(defn- ajax-cbs [sch]
  {:write-failed  (fn [_throwable] (jetty9.websocket/close! sch))
   :write-success (fn [          ] (jetty9.websocket/close! sch))})

(extend-type org.eclipse.jetty.websocket.api.WebSocketAdapter
  i/IServerChan
  (sch-open?  [sch] (jetty9.websocket/connected? sch))
  (sch-close! [sch] (jetty9.websocket/close!     sch))
  (sch-send!  [sch websocket? msg]
    (if websocket?
      (jetty9.websocket/send! sch msg)
      (jetty9.websocket/send! sch msg (ajax-cbs sch)))))

(defn- server-ch-resp
  [websocket? {:keys [on-open on-close on-msg on-error ring-async-resp-fn ring-async-raise-fn]}]
  {:on-connect          (fn [sch         ] (on-open  sch websocket?))
   :on-text             (fn [sch msg     ] (on-msg   sch websocket? msg))
   :on-error            (fn [sch error   ] (on-error sch websocket? error))
   :on-close            (fn [sch status _] (on-close sch websocket? status))
   :ring-async-resp-fn  ring-async-resp-fn
   :ring-async-raise-fn ring-async-raise-fn})

(deftype JettyServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [_ req callbacks-map]
    (jetty9.websocket/ws-upgrade-response
      (server-ch-resp (jetty9.websocket/ws-upgrade-request? req) callbacks-map))))

(defn get-sch-adapter [] (JettyServerChanAdapter.))

(comment
  (require '[ring.adapter.jetty9 :refer [run-jetty stop-server]]
           '[taoensso.sente :as sente]
           '[compojure.core :refer :all])

  (let [{:keys [ch-recv send-fn connected-uids
                ajax-post-fn ajax-get-or-ws-handshake-fn]}
        (sente/make-channel-socket! (get-sch-adapter) {})]
    (def ring-ajax-post                ajax-post-fn)
    (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
    (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
    (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
    (def connected-uids                connected-uids)) ; Watchable, read-only atom

  (defroutes my-app-routes
    ;; <other stuff>

    ;;; Add these 2 entries: --->
    (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
    (POST "/chsk" req (ring-ajax-post                req)))

  (def server (atom nil))
  (reset! server (run-jetty my-app-routes {:port 50505
                                           :join? false
                                           :async? true}))
  (stop-server @server)

  (let [{:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket-client!
         "/chsk" ; Note the same path as before
         "foo"
         {:type :ws})] ; e/o #{:auto :ajax :ws}

   (def chsk       chsk)
   (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
   (def chsk-send! send-fn) ; ChannelSocket's send API fn
   (def chsk-state state)))   ; Watchable, read-only atom
