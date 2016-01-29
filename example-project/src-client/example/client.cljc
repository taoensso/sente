(ns example.client
  "Sente client reference example
  ---------------------------------------------------------------------------
  This example dives into Sente's full functionality quickly; it's probably
  more useful as a reference than a tutorial. See the GitHub README for a
  gentler intro.

  Instructions:
    1. Call `lein start` at your terminal, should auto-open web browser
    2. Observe std-out (server log) and web page textarea (client log)"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  #?(:clj
     (:require
      [clojure.string     :as str]
      [ring.middleware.defaults]
      [compojure.core     :as comp :refer (defroutes GET POST)]
      [compojure.route    :as route]
      [hiccup.core        :as hiccup]
      [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
      [taoensso.encore    :as encore :refer ()]
      [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
      [taoensso.sente     :as sente]

      ;; Optional, for Transit encoding:
      [taoensso.sente.packers.transit :as sente-transit]))

  #?(:cljs
     (:require
      [clojure.string  :as str]
      [cljs.core.async :as async  :refer (<! >! put! chan)]
      [taoensso.encore :as encore :refer ()]
      [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
      [taoensso.sente  :as sente  :refer (cb-success?)]

      ;; Optional, for Transit encoding:
      [taoensso.sente.packers.transit :as sente-transit]))

  #?(:cljs
     (:require-macros
      [cljs.core.async.macros :as asyncm :refer (go go-loop)])))

;;;; Logging config

;; (timbre/set-level! :trace) ; Uncomment for more logging

;;;; Packer (client<->server serializtion format) config

(def packer
  :edn ; Default packer, a good choice in most cases
  ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit dep
  )

;;;; Client-side setup

#?(:cljs (def output-el (.getElementById js/document "output")))
#?(:cljs
(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el)))))

#?(:cljs (->output! "ClojureScript appears to have loaded correctly."))
#?(:cljs
(let [rand-chsk-type (if (>= (rand) 0.5) :ajax :auto)

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same URL as before
        {:type   rand-chsk-type
         :packer packer})]
  (->output! "Randomly selected chsk type: %s" rand-chsk-type)
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  ))

;;;; Routing handlers

;; So you'll want to define one server-side and one client-side
;; (fn event-msg-handler [ev-msg]) to correctly handle incoming events. How you
;; actually do this is entirely up to you. In this example we use a multimethod
;; that dispatches to a method based on the `event-msg`'s event-id. Some
;; alternatives include a simple `case`/`cond`/`condp` against event-ids, or
;; `core.match` against events.

(defmulti event-msg-handler :id) ; Dispatch on event-id

;; Wrap for logging, catching, etc.:
(defn event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (event-msg-handler ev-msg))

#?(:cljs
   (do ; Client-side methods
     (defmethod event-msg-handler :default ; Fallback
       [{:as ev-msg :keys [event]}]
       (->output! "Unhandled event: %s" event))

     (defmethod event-msg-handler :chsk/state
       [{:as ev-msg :keys [?data]}]
       (if (= ?data {:first-open? true})
         (->output! "Channel socket successfully established!")
         (->output! "Channel socket state change: %s" ?data)))

     (defmethod event-msg-handler :chsk/recv
       [{:as ev-msg :keys [?data]}]
       (->output! "Push event from server: %s" ?data))

     (defmethod event-msg-handler :chsk/handshake
       [{:as ev-msg :keys [?data]}]
       (let [[?uid ?csrf-token ?handshake-data] ?data]
         (->output! "Handshake: %s" ?data)))

     ;; Add your (defmethod handle-event-msg! <event-id> [ev-msg] <body>)s here...
     ))

;;;; Client-side UI

#?(:cljs
(do
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
                 :params {:user-id    (str user-id)
                          :csrf-token (:csrf-token @chsk-state)}}
                (fn [ajax-resp]
                  (->output! "Ajax login response: %s" ajax-resp)
                  (let [login-successful? true ; Your logic here
                        ]
                    (if-not login-successful?
                      (->output! "Login failed")
                      (do
                        (->output! "Login successful")
                        (sente/chsk-reconnect! chsk))))))))))))))

;;;; Init

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))

#?(:cljs
(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*))))

#?(:cljs
(defn start! []
  (start-router!)))

#?(:cljs   (defonce _start-once (start!)))
;; #?(:clj (defonce _start-once (start!)))

;; Disabled for now, until the example is updated for running with
;; Clojure as the client.
;; #?(:clj
;; (defn -main "For `lein run`, etc." [] (start!)))
