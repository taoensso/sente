(ns example.client
  "Official Sente reference example: client"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer-macros (have have?)]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente  :as sente  :refer (cb-success?)]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit])

  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;; (timbre/set-level! :trace) ; Uncomment for more logging

;;;; Util for logging output to on-screen console

(def output-el (.getElementById js/document "output"))
(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(->output! "ClojureScript appears to have loaded correctly.")

;;;; Define our Sente channel socket (chsk) client

(let [;; For this example, select a random protocol:
      rand-chsk-type (if (>= (rand) 0.5) :ajax :auto)
      _ (->output! "Randomly selected chsk type: %s" rand-chsk-type)

      ;; Serializtion format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk" ; Must match server Ring routing URL
        {:type   rand-chsk-type
         :packer packer})]

  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )

;;;; Sente event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (->output! "Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (let [[old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (->output! "Channel socket successfully established!: %s" new-state-map)
      (->output! "Channel socket state change: %s"              new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (->output! "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (->output! "Handshake: %s" ?data)))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-client-chsk-router!
      ch-chsk event-msg-handler)))

;;;; UI events

(when-let [target-el (.getElementById js/document "btn1")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Button 1 was clicked (won't receive any reply from server)")
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

(when-let [target-el (.getElementById js/document "btn2")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Button 2 was clicked (will receive reply from server)")
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply] (->output! "Callback reply: %s" cb-reply))))))

(when-let [target-el (.getElementById js/document "btn3")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Button 3 was clicked (will ask server to test rapid async push)")
      (chsk-send! [:example/test-rapid-push]))))

(when-let [target-el (.getElementById js/document "btn4")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Button 4 was clicked (will toggle async broadcast loop)")
      (chsk-send! [:example/toggle-broadcast] 5000
        (fn [cb-reply]
          (when (cb-success? cb-reply)
            (let [loop-enabled? cb-reply]
              (if loop-enabled?
                (->output! "Async broadcast loop now enabled")
                (->output! "Async broadcast loop now disabled")))))))))

(when-let [target-el (.getElementById js/document "btn5")]
  (.addEventListener target-el "click"
                     (fn [ev]
                       (->output! "Disconnecting")
                       (sente/chsk-disconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn6")]
  (.addEventListener target-el "click"
                     (fn [ev]
                       (->output! "Reconnecting")
                       (sente/chsk-reconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (->output! "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.

            (sente/ajax-lite "/login"
              {:method :post
               :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
               :params  {:user-id (str user-id)}}

              (fn [ajax-resp]
                (->output! "Ajax login response: %s" ajax-resp)
                (let [login-successful? true ; Your logic here
                      ]
                  (if-not login-successful?
                    (->output! "Login failed")
                    (do
                      (->output! "Login successful")
                      (sente/chsk-reconnect! chsk))))))))))))

;;;; Init stuff

(defn start! [] (start-router!))

(defonce _start-once (start!))
