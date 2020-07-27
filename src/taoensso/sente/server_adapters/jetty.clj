(ns taoensso.sente.server-adapters.jetty
  "Sente adapter for ring-jetty9-adapter (https://github.com/sunng87/ring-jetty9-adapter)

  Note: ring-jetty9-adapter requires its own middleware stack for websocket connections.

  Thus, stateful Ring middleware require a little extra configuration:

  * ring-session: pass shared instance of :store
  * ring-anti-forgery: pass shared instance of :strategy"
  {:author "Thomas Crowley (@wavejumper)"}
  (:require [clojure.string :as str]
            [ring.adapter.jetty9.websocket :as jetty9.websocket]
            [taoensso.sente.interfaces :as i])
  (:import (org.eclipse.jetty.websocket.api WebSocketAdapter)))

(defn ajax-cbs [ws]
  {:write-failed  (fn [_] (jetty9.websocket/close! ws))
   :write-success (fn [_] (jetty9.websocket/close! ws))})

(extend-protocol i/IServerChan
  WebSocketAdapter
  (sch-open? [ws]
    (jetty9.websocket/connected? ws))

  (sch-close! [ws]
    (jetty9.websocket/close! ws))

  (sch-send! [ws ws? msg]
    (if ws?
      (jetty9.websocket/send! ws msg)
      (jetty9.websocket/send! ws msg (ajax-cbs ws)))))

(defrecord JettyServerChanResponse [ws]
  jetty9.websocket/IWebSocketAdapter
  (ws-adapter [_] ws))

(defn server-ch-resp
  [ws? {:keys [on-open on-close on-msg on-error]}]
  (let [ws (jetty9.websocket/proxy-ws-adapter
            {:on-connect (fn [ws]
                           (on-open ws ws?))
             :on-text    (fn [ws msg]
                           (on-msg ws ws? msg))
             :on-close   (fn [ws status-code _]
                           (on-close ws ws? status-code))
             :on-error   (fn [ws e]
                           (on-error ws ws? e))})]
    (JettyServerChanResponse. ws)))

(defn- websocket-req? [ring-req]
  (when-let [s (get-in ring-req [:headers "upgrade"])]
    (= "websocket" (str/lower-case s))))

(deftype JettyServerChanAdapter []
  i/IServerChanAdapter
  (ring-req->server-ch-resp [_ req callbacks-map]
    (server-ch-resp (websocket-req? req) callbacks-map)))

(defn get-sch-adapter []
  (JettyServerChanAdapter.))