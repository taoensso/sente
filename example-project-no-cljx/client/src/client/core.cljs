(ns client.core
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer (logf)]
   [taoensso.sente  :as sente  :refer (cb-success?)]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit]))

(def packer
  "Defines our packing (serialization) format for client<->server comms."
  :edn ; Default
  ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit deps
  )

(logf "ClojureScript appears to have loaded correctly.")

(let [random-chsk-type-for-fun (if (>= (rand) 0.5) :ajax :auto)

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same URL as before
        {:type   random-chsk-type-for-fun
         :packer packer})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )

;;;; Routing handlers

;; So you'll want to define one server-side and one client-side
;; (fn event-msg-handler [ev-msg]) to correctly handle incoming events. How you
;; actually do this is entirely up to you. In this example we use a multimethod
;; that dispatches to a method based on the `event-msg`'s event-id. Some
;; alternatives include a simple `case`/`cond`/`condp` against event-ids, or
;; `core.match` against events.

(defmulti event-msg-handler :id) ; Dispatch on event-id
;; Wrap for logging, catching, etc.:
(defn     event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (logf "Event: %s" event)
  (event-msg-handler ev-msg))

(do ; Client-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event]}]
    (logf "Unhandled event: %s" event))

  (defmethod event-msg-handler :chsk/state
    [{:as ev-msg :keys [?data]}]
    (if (= ?data {:first-open? true})
      (logf "Channel socket successfully established!")
      (logf "Channel socket state change: %s" ?data)))

  (defmethod event-msg-handler :chsk/recv
    [{:as ev-msg :keys [?data]}]
    (logf "Push event from server: %s" ?data))

  ;; Add your (defmethod handle-event-msg! <event-id> [ev-msg] <body>)s here...
  )

;;;; Client-side UI

(when-let [target-el (.getElementById js/document "btn1")]
  (.addEventListener target-el "click"
    (fn [ev]
      (logf "Button 1 was clicked (won't receive any reply from server)")
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

(when-let [target-el (.getElementById js/document "btn2")]
  (.addEventListener target-el "click"
    (fn [ev]
      (logf "Button 2 was clicked (will receive reply from server)")
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply] (logf "Callback reply: %s" cb-reply))))))

(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (logf "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.

            (sente/ajax-call "/login"
              {:method :post
               :params {:user-id    (str user-id)
                        :csrf-token (:csrf-token @chsk-state)}}
              (fn [ajax-resp] (logf "Ajax login response: %s" ajax-resp)))

            (sente/chsk-reconnect! chsk)))))))

(def router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defn start! []
  (start-router!))

(start!)
