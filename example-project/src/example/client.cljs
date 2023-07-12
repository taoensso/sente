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

(defonce   min-log-level_ (atom nil))
(defn- set-min-log-level! [level]
  (sente/set-min-log-level! level) ; Min log level for internal Sente namespaces
  (timbre/set-ns-min-level! level) ; Min log level for this           namespace
  (reset! min-log-level_    level))

(when-let [el (.getElementById js/document "sente-min-log-level")]
  (let [level (if-let [attr (.getAttribute el "data-level")]
                (keyword attr)
                :warn)]
    (set-min-log-level! level)))

;;;; Util for logging output to on-screen console

(let [output-el (.getElementById js/document "output")]
  (defn- ->output!! [x]
    (aset output-el "value"     (str   (.-value output-el) x))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(defn ->output!
  ([          ] (->output!! "\n"))
  ([fmt & args]
   (let [msg (apply encore/format fmt args)]
     (->output!! (str "\n• " msg)))))

(->output! "ClojureScript has successfully loaded")
(->output! "Sente version: %s" sente/sente-version)
(->output! "Min log level: %s (use toggle button to change)" @min-log-level_)
(->output!)

;;;; Define our Sente channel socket (chsk) client

(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-token")))

(if ?csrf-token
  (->output! "CSRF token detected in HTML, great!")
  (->output! "**IMPORTANT** CSRF token NOT detected in HTML, default Sente config will reject requests!"))

(def chsk-type
  "We'll select a random protocol for this example"
  (if (>= (rand) 0.5) :ajax :auto))

(->output! "Randomly selected chsk type: %s" chsk-type)
(->output!)

(let [;; Serializtion format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk" ; Must match server Ring routing URL
        ?csrf-token
        {:type   chsk-type
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

(when-let [target-el (.getElementById js/document "btn-send-with-reply")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply]
          (->output! "Callback reply: %s" cb-reply))))))

(when-let [target-el (.getElementById js/document "btn-send-wo-reply")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

(when-let [target-el (.getElementById js/document "btn-test-broadcast")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (chsk-send! [:example/test-broadcast]))))

(when-let [target-el (.getElementById js/document "btn-toggle-broadcast-loop")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-broadcast-loop] 5000
        (fn [cb-reply]
          (when (cb-success? cb-reply)
            (let [enabled? cb-reply]
              (if enabled?
                (->output! "Server broadcast loop now ENABLED")
                (->output! "Server broadcast loop now DISABLED")))))))))

(when-let [target-el (.getElementById js/document "btn-disconnect")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (sente/chsk-disconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn-reconnect")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (sente/chsk-reconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn-break-with-close")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (sente/chsk-break-connection! chsk {:close-ws? true}))))

(when-let [target-el (.getElementById js/document "btn-break-wo-close")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (sente/chsk-break-connection! chsk {:close-ws? false}))))

(when-let [target-el (.getElementById js/document "btn-toggle-logging")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-min-log-level] 5000
        (fn [cb-reply]
          (if (cb-success? cb-reply)
            (let [level cb-reply]
              (set-min-log-level! level)
              (->output! "New minimum log level (client+server): %s" level))
            (->output! "Request failed: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-toggle-bad-conn-rate")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-bad-conn-rate] 5000
        (fn [cb-reply]
          (if (cb-success? cb-reply)
            (->output! "New rate: %s"       cb-reply)
            (->output! "Request failed: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-connected-uids")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/connected-uids] 5000
        (fn [cb-reply]
          (when (cb-success? cb-reply)
            (->output! "Connected uids: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (->output!)
            (->output! "Logging in with user-id %s..." user-id)

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

(when-let [target-el (.getElementById js/document "btn-repeated-logins")]
  (.addEventListener target-el "click"
    (fn [ev]
      (->output!)
      (->output! "Will rapidly change user-id from \"1\" to \"10\"...")
      (let [c (async/chan)]
        (go-loop [uids (range 11)]
          (when-let [[next-uid] uids]
            (sente/ajax-lite "/login"
              {:method :post
               :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
               :params  {:user-id (str next-uid)}}
              (fn [ajax-resp]
                (when (:success? ajax-resp) (sente/chsk-reconnect! chsk))
                (put! c :continue)))
            (<! c)
            (<! (async/timeout 100))
            (recur (next uids))))))))

;;;; Init stuff

(defn start! [] (start-router!))
(defonce _start-once (start!))
