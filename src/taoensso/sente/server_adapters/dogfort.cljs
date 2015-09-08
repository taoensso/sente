(ns taoensso.sente.server-adapters.dogfort
  "Sente on node.js with dogfort (https://github.com/whamtet/dogfort)"
  {:author "Matthew Molloy (whamtet@gmail.com)"}
  (:require [taoensso.sente.interfaces :as i]))

(def
  "we handle two cases: ajax and websocket."
  sente-web-server-adapter
  (reify
    i/IAsyncNetworkChannelAdapter
    (ring-req->net-ch-resp
     [_ ring-req callbacks-map]
     (let [
           {:keys [on-open on-close on-msg]} callbacks-map
           {:keys [websocket response body]} ring-req
           response-open? (atom true)
           write #(try
                    (.write %1 %2)
                    (catch :default e))
           chan
           (if websocket

             (reify i/IAsyncNetworkChannel
               (send!* [this msg close-after-send?]
                       "Sends a message to channel."
                       (let [
                             pre-open? (= (.-readyState websocket) (.-OPEN websocket))
                             ]
                         (.send websocket msg)
                         (when close-after-send?
                           (.close websocket))
                         pre-open?))
               (open?  [net-ch]
                       "Returns true iff the channel is currently open."
                       (= (.-readyState websocket) (.-OPEN websocket)))
               (close! [net-ch] "Closes the channel."
                       (.close websocket)))

             (reify i/IAsyncNetworkChannel
               (send!* [this msg close-after-send?]
                       (let [
                             pre-open? @response-open?
                             ]
                         (if close-after-send?
                           (do
                             (reset! response-open? false)
                             (.end response msg))
                           (write response msg))
                         pre-open?))
               (open? [this] @response-open?)
               (close! [this]
                       (if @response-open?
                         (.end response))
                       (reset! response-open? false))))
           ]
       (on-open chan)
       (if websocket
         (do
           (.on websocket
                "message"
                (fn [data flags]
                  (on-msg chan data)))
           (.onclose websocket
                     (fn [code message]
                       (on-close chan code))))
         (do
           (.on body "data"
                (fn [data]
                  (on-msg chan data)))
           #_(.on response ;bad, bad!
                "finish"
                #(on-close chan))))

       ;;for ajax connections if we reply blank then the route matcher will fail.
       ;;dogfort will send a 404 response and close the connection.
       ;;to keep it open we just send this instead of a ring response.
       {:keep-alive true}
       ))))
