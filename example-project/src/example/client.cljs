(ns example.client
  "Official Sente reference example: client"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer [<! >! put! chan]]
   [taoensso.encore :as encore :refer-macros [have have?]]
   [taoensso.timbre :as timbre :refer-macros []]
   [taoensso.sente  :as sente  :refer [cb-success?]]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit])

  (:require-macros
   [cljs.core.async.macros :as asyncm :refer [go go-loop]]))

;;;; Logging config

(defn- set-min-log-level! [level]
  (sente/set-min-log-level! level) ; Min log level for internal Sente namespaces
  (timbre/set-ns-min-level! level) ; Min log level for this           namespace
  )

(set-min-log-level! :info)

;;;; Util for logging output to on-screen console

(def output-el (.getElementById js/document "output"))
(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    ;; (timbre/tracef "->output: %s" msg)
    (aset output-el "value" (str (.-value output-el) "\n• " msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(->output! "ClojureScript has successfully loaded")

;;;; Define our Sente channel socket (chsk) client

(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-csrf-token")))

(if ?csrf-token
  (->output! "CSRF token detected in HTML, great!")
  (->output! "**IMPORTANT** CSRF token NOT detected in HTML, default Sente config will reject requests!"))

(let [;; For this example, select a random protocol:
      rand-chsk-type (if (>= (rand) 0.5) :ajax :auto)
      _ (->output! "Randomly selected chsk type: %s" rand-chsk-type)

      ;; Serializtion format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk" ; Must match server Ring routing URL
        ?csrf-token
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
    (cond
      ;; Tip: look for {:keys [opened? closed? first-open?]} in `new-state-map` to
      ;; easily identify these commonly useful state transitions
      (:first-open? new-state-map) (->output! "Channel socket FIRST OPENED: %s"  new-state-map)
      (:opened?     new-state-map) (->output! "Channel socket OPENED: %s"        new-state-map)
      (:closed?     new-state-map) (->output! "Channel socket CLOSED: %s"        new-state-map)
      :else                        (->output! "Channel socket state changed: %s" new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (->output! "Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid _ ?handshake-data first-handshake?] ?data]
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
      (->output! "Will send event to server WITH callback")
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply] (->output! "Callback reply: %s" cb-reply))))))

(when-let [target-el (.getElementById js/document "btn2")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Will send event to server WITHOUT callback")
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

(when-let [target-el (.getElementById js/document "btn3")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Will ask server to test rapid async push")
      (chsk-send! [:example/test-rapid-push]))))

(when-let [target-el (.getElementById js/document "btn4")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-broadcast] 5000
        (fn [cb-reply]
          (when (cb-success? cb-reply)
            (let [loop-enabled? cb-reply]
              (if loop-enabled?
                (->output! "Server async broadcast loop now ENABLED")
                (->output! "Server async broadcast loop now DISABLED")))))))))

(when-let [target-el (.getElementById js/document "btn5")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Disconnecting...\n\n")
      (sente/chsk-disconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn6")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Reconnecting...\n\n")
      (sente/chsk-reconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn7")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Simulating basic broken connection (WITH close)...\n\n")
      (sente/chsk-break-connection! chsk {:close-ws? true}))))

(when-let [target-el (.getElementById js/document "btn8")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Simulating basic broken connection (WITHOUT close)...\n\n")
      (sente/chsk-break-connection! chsk {:close-ws? false}))))

(when-let [target-el (.getElementById js/document "btn9")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output! "Will ask server to toggle minimum log level")
      (chsk-send! [:example/toggle-min-log-level] 5000
        (fn [cb-reply]
          (if (cb-success? cb-reply)
            (let [level cb-reply]
              (set-min-log-level! level)
              (->output! "New minimum log level (client+server): %s" level))
            (->output! "Failed to toggle minimum log level: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (->output! "Logging in with user-id %s...\n\n" user-id)

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
