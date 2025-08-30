(ns example.client
  "Official Sente reference example: client"
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async]
   [taoensso.encore :as encore]
   [taoensso.sente  :as sente]

   [taoensso.telemere :as tel]
   [taoensso.trove]
   [taoensso.trove.telemere]

   [example.dynamic-packer]))

;;;; Logging

(taoensso.trove/set-log-fn! (taoensso.trove.telemere/get-log-fn))

(defonce   min-log-level_  (atom nil))
(defn- set-min-log-level! [level]
  (tel/set-min-level!      level)
  (reset!  min-log-level_  level))

;;;; Init config

(def init-config
  "{:keys [csrf-token min-log-level packer-mode]}"
  (when-let   [el  (.getElementById js/document "init-config")]
    (when-let [edn (.getAttribute el "data-edn")]
      (encore/read-edn edn))))

(set-min-log-level!                  (get init-config :min-log-level))
(reset! example.dynamic-packer/mode_ (get init-config :packer-mode))

;;;; On-screen console

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
(->output! "Init config: %s" init-config)
(->output!)

;;;; Define our Sente channel socket (chsk) client

(def ?csrf-token (get init-config :csrf-token))
(if  ?csrf-token
  (->output! "CSRF token in init config, great!")
  (->output! "**IMPORTANT** no CSRF token in init config, default Sente config will reject requests!"))

(def chsk-type
  "We'll select a random protocol for this example"
  (if (>= (rand) 0.5) :ajax :auto))

(->output! "Randomly selected chsk type: %s" chsk-type)
(->output!)

(def packer
  "Sente uses \"packers\" to control how values are encoded during
  client<->server transit.

  Default is to use edn, but this reference example uses a dynamic
  packer that can swap between several packers for testing.

  Client and server should use the same packer."

  #_:edn ; Default
  #_(taoensso.sente.packers.transit/get-packer)
  #_(taoensso.sente.packers.msgpack/get-packer) ; Experimental
  (example.dynamic-packer/get-packer) ; For testing
  )

(def chsk-client
  (sente/make-channel-socket-client!
    "/chsk" ; Must match server Ring routing URL
    ?csrf-token
    {:type   chsk-type
     :packer packer}))

(let [{:keys [chsk ch-recv send-fn state]} chsk-client]
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
  (let [[old-state-map new-state-map] (encore/have vector? ?data)]
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
          (when (sente/cb-success? cb-reply)
            (let [enabled?         cb-reply]
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
      (chsk-send! [:example/toggle-log-level] 5000
        (fn [cb-reply]
          (when (sente/cb-success? cb-reply)
            (let [new-level        cb-reply]
              (set-min-log-level!            new-level)
              (->output! "New log level: %s" new-level))))))))

(when-let [target-el (.getElementById js/document "btn-toggle-packer")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-packer] 5000
        (fn [cb-reply]
          (when         (sente/cb-success? cb-reply)
            (when-let [[old-mode new-mode] cb-reply]
              (->output! "Changing packer mode: %s -> %s" old-mode new-mode)
              (reset! example.dynamic-packer/mode_                 new-mode))))))))

(when-let [target-el (.getElementById js/document "btn-toggle-bad-conn-rate")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/toggle-bad-conn-rate] 5000
        (fn [cb-reply]
          (when (sente/cb-success?    cb-reply)
            (->output! "New rate: %s" cb-reply)))))))

(when-let [target-el (.getElementById js/document "btn-connected-uids")]
  (.addEventListener target-el "click"
    (fn [ev]
      (chsk-send! [:example/connected-uids] 5000
        (fn [cb-reply]
          (when (sente/cb-success?          cb-reply)
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

            (sente/ajax-call "/login"
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
        (async/go-loop [uids (range 11)]
          (when-let [[next-uid] uids]
            (sente/ajax-call "/login"
              {:method :post
               :headers {:X-CSRF-Token (:csrf-token @chsk-state)}
               :params  {:user-id (str next-uid)}}
              (fn [ajax-resp]
                (when (:success? ajax-resp) (sente/chsk-reconnect! chsk))
                (async/put! c :continue)))
            (async/<! c)
            (async/<! (async/timeout 100))
            (recur (next uids))))))))

;;;; Init stuff

(defn start! [] (start-router!))
(defonce _start-once (start!))
