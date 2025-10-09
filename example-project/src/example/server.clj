(ns example.server
  "Official Sente reference example: server"
  (:require
   [clojure.string     :as str]
   [compojure.core     :as comp :refer [defroutes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async]
   [taoensso.encore    :as encore]
   [taoensso.sente     :as sente]

   [taoensso.telemere  :as tel]
   [taoensso.trove]
   [taoensso.trove.telemere]

   [ring.middleware.defaults]
   [ring.middleware.anti-forgery :as anti-forgery]

   [example.dynamic-packer]

   ;; http-kit
   [org.httpkit.server :as http-kit]
   [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]

   ;; Immutant
   #_[immutant.web :as immutant]
   #_[taoensso.sente.server-adapters.immutant :refer [get-sch-adapter]]

   ;; nginx-clojure
   #_[nginx.clojure.embed :as nginx-clojure]
   #_[taoensso.sente.server-adapters.nginx-clojure :refer [get-sch-adapter]]

   ;; Aleph
   #_[aleph.http :as aleph]
   #_[taoensso.sente.server-adapters.aleph :refer [get-sch-adapter]]

   ;; Jetty 9, Ref .<https://gist.github.com/wavejumper/40c4cbb21d67e4415e20685710b68ea0>
   #_[ring.adapter.jetty9.websocket :as jetty9.websocket]
   #_[taoensso.sente.server-adapters.jetty9 :refer [get-sch-adapter]]))

;;;; Logging

(taoensso.trove/set-log-fn! (taoensso.trove.telemere/get-log-fn))

(defonce   min-log-level_ (atom nil))
(defn- set-min-log-level! [level]
  (tel/set-min-level!      level)
  (reset!  min-log-level_  level))

(set-min-log-level! #_:trace :debug #_:info #_:warn)

;;;; Define our Sente channel socket (chsk) server

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

(defonce chsk-server
  (sente/make-channel-socket-server!
    (get-sch-adapter) {:packer packer}))

(let [{:keys [ch-recv send-fn connected-uids_ private
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]

  (defonce ring-ajax-post                ajax-post-fn)
  (defonce ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (defonce ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (defonce chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (defonce connected-uids_               connected-uids_)   ; Watchable, read-only atom
  (defonce conns_                        (:conns_ private)) ; Implementation detail, for debugging!
  )

;; We can watch this atom for changes
(add-watch connected-uids_ :connected-uids
  (fn [_ _ old new]
    (when (not= old new)
      (tel/log! (str "Connected uids changed to:" new)))))

;;;; Ring handlers

(defn landing-pg-handler [ring-req]
  (hiccup/html

    [:div#init-config
     {:data-edn
      (encore/pr-edn
        {:csrf-token    (or (get ring-req :anti-forgery-token) (force anti-forgery/*anti-forgery-token*))
         :min-log-level @min-log-level_
         :packer-mode   @example.dynamic-packer/mode_})}]

    [:h3 "Sente reference example"]
    [:p
     "A " [:i "random"] " " [:strong [:code ":ajax/:auto"]]
     " connection mode has been selected (see " [:strong "client output"] ")."
     [:br]
     "To " [:strong "re-randomize"] ", hit your browser's reload/refresh button."]
    [:ul
     [:li [:strong "Server output:"] " → " [:code "*std-out*"]]
     [:li [:strong "Client output:"] " → Below textarea and/or browser console"]]
    [:textarea#output {:style "width: 100%; height: 200px;" :wrap "off"}]

    [:section
     [:h4 "Standard Controls"]
     [:p
      [:button#btn-send-with-reply {:type "button"} "chsk-send! (with reply)"] " "
      [:button#btn-send-wo-reply   {:type "button"} "chsk-send! (without reply)"] " "]
     [:p
      [:button#btn-test-broadcast        {:type "button"} "Test broadcast (server>user async push)"] " "
      [:button#btn-toggle-broadcast-loop {:type "button"} "Toggle broadcast loop"]]
     [:p
      [:button#btn-disconnect {:type "button"} "Disconnect"] " "
      [:button#btn-reconnect  {:type "button"} "Reconnect"]]
     [:p
      [:button#btn-login  {:type "button"} "Log in with user-id →"] " "
      [:input#input-login {:type :text :placeholder "user-id"}]]
     [:ul {:style "color: #808080; font-size: 0.9em;"}
      [:li "Log in with a " [:a {:href "https://github.com/ptaoussanis/sente/wiki/Client-and-user-ids#user-ids" :target :_blank} "user-id"]
       " so that the server can directly address that user's connected clients."]
      [:li "Open this page with " [:strong "multiple browser windows"] " to simulate multiple clients."]
      [:li "Use different browsers and/or " [:strong "Private Browsing / Incognito mode"] " to simulate multiple users."]]]

    [:hr]

    [:section
     [:h4 "Debug and Testing Controls"]
     [:p
      [:button#btn-toggle-logging {:type "button"} "Toggle log level"] " "
      [:button#btn-toggle-packer  {:type "button"} "Toggle packer"]]
     [:p
      [:button#btn-break-with-close     {:type "button"} "Break conn (with on-close)"] " "
      [:button#btn-break-wo-close       {:type "button"} "Break conn (w/o on-close)"] " "
      [:button#btn-toggle-bad-conn-rate {:type "button"} "Toggle simulated bad conn rate"]]
     [:p
      [:button#btn-repeated-logins  {:type "button"} "Test repeated logins"] " "
      [:button#btn-connected-uids   {:type "button"} "Print connected uids"]]]

    [:script {:src "main.js"}] ; Include our cljs target
    ))

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (tel/log! {:level :debug, :msg "Login request", :data {:params params}})
    {:status 200 :session (assoc session :uid user-id)}))

(defroutes ring-routes
  (GET  "/"      ring-req (landing-pg-handler            ring-req))
  (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk"  ring-req (ring-ajax-post                ring-req))
  (POST "/login" ring-req (login-handler                 ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  "**NB**: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
  middleware to work. These are included with
  `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
  that they're included yourself if you're not using `wrap-defaults`.

  You're also STRONGLY recommended to use `ring.middleware.anti-forgery`
  or something similar."
  (ring.middleware.defaults/wrap-defaults
    ring-routes ring.middleware.defaults/site-defaults))

;;;; Some server>user async push examples

(defn broadcast!
  "Pushes given event to all connected users."
  [event]
  (let [all-uids (:any @connected-uids_)]
    (doseq [uid all-uids]
      (tel/log! {:level :debug, :msg (format "Broadcasting server>user to %s uids" (count all-uids))})
      (chsk-send! uid event))))

(defn test-broadcast!
  "Quickly broadcasts 100 events to all connected users.
  Note that this'll be fast+reliable even over Ajax!"
  []
  (doseq [uid (:any @connected-uids_)]
    (doseq [i (range 100)]
      (chsk-send! uid [:example/broadcast {:i i, :uid uid}]))))

(comment (test-broadcast!))

(defonce broadcast-loop?_ (atom true))
(defonce ^:private auto-loop_
  (delay
    (async/go-loop [i 0]
      (async/<! (async/timeout 10000)) ; 10 secs

      (tel/log! :debug (str "Connected uids: " @connected-uids_))
      (tel/log! :trace (str "Conns state: "    @conns_))

      (when @broadcast-loop?_
        (broadcast!
          [:example/broadcast-loop
           {:my-message "A broadcast, pushed asynchronously from server"
            :i i}]))

      (recur (inc (long i))))))

;;;; Sente event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg) ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (tel/log! :debug (str "Unhandled event: " event))
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))

(defmethod -event-msg-handler :chsk/uidport-open
  [{:as ev-msg :keys [ring-req]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (if uid
      (tel/log! (str "User connected: user-id " uid))
      (tel/log!      "User connected: no user-id (user didn't have login session)"))))

(defmethod -event-msg-handler :chsk/uidport-close
  [{:as ev-msg :keys [ring-req]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (if uid
      (tel/log! (str "User disconnected: user-id " uid))
      (tel/log!      "User disconnected: no user-id (user didn't have login session)"))))

(defmethod -event-msg-handler :example/test-broadcast
  [ev-msg] (test-broadcast!))

(defmethod -event-msg-handler :example/toggle-broadcast-loop
  [{:as ev-msg :keys [?reply-fn]}]
  (let [loop-enabled? (swap! broadcast-loop?_ not)]
    (?reply-fn loop-enabled?)))

(defmethod -event-msg-handler :example/toggle-log-level
  [{:as ev-msg :keys [?reply-fn]}]
  (let [new-level
        (case @min-log-level_
          :trace :debug
          :debug :info
          :info  :warn
          :warn  :error
          :error :trace
          :trace)]

    (set-min-log-level! new-level)
    (?reply-fn          new-level)))

(defmethod -event-msg-handler :example/toggle-packer
  [{:as ev-msg :keys [?reply-fn]}]
  (let [old-mode @example.dynamic-packer/mode_
        new-mode
        (case old-mode
          :edn/txt    :edn/bin
          :edn/bin    :transit
          :transit    :msgpack
          :msgpack    :msgpack+gz
          :msgpack+gz :edn/txt)]

    (tel/log! (str "Changing packer mode: " old-mode " -> " new-mode))
    (?reply-fn [old-mode new-mode])
    (reset! example.dynamic-packer/mode_ new-mode)))

(defmethod -event-msg-handler :example/toggle-bad-conn-rate
  [{:as ev-msg :keys [?reply-fn]}]
  (let [new-rate
        (case sente/*simulated-bad-conn-rate*
          nil  0.25
          0.25 0.5
          0.5  0.75
          0.75 1.0
          1.0  nil)]

    (alter-var-root #'sente/*simulated-bad-conn-rate* (constantly new-rate))
    (?reply-fn new-rate)))

(defmethod -event-msg-handler :example/connected-uids
  [{:as ev-msg :keys [?reply-fn]}]
  (let [uids @connected-uids_]
    (tel/log! (str "Connected uids: " uids))
    (?reply-fn                        uids)))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-fn @router_] (stop-fn)))
(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))

;;;; Init stuff

(encore/defonce web-server_ "?{:keys [port stop-fn]}" (atom nil))

(defn  stop-web-server! [] (when-let [{:keys [stop-fn]} @web-server_] (stop-fn)))
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [port         (or port 0) ; 0 => Choose any available port
        ring-handler (var main-ring-handler)

        ;; http-kit
        {:keys [port stop-fn]}
        (let [server (http-kit/run-server ring-handler {:port port, :legacy-return-value? false})]
          {:port           (http-kit/server-port  server)
           :stop-fn (fn [] (http-kit/server-stop! server {:timeout 100}))})

        ;; Immutant
        #_{:keys [port stop-fn]}
        #_(let [server (immutant/run ring-handler :port port)]
          {:port    (:port server)
           :stop-fn (fn [] (immutant/stop server))})

        ;; nginx-clojure
        #_{:keys [port stop-fn]}
        #_
        (let [port (nginx-clojure/run-server ring-handler {:port port})]
          {:port    port
           :stop-fn (fn [] (nginx-clojure/stop-server))})

        ;; Aleph
        #_{:keys [port stop-fn]}
        #_
        (let [server (aleph/start-server ring-handler {:port port})
              p (promise)]
          (future @p) ; Workaround for Ref. <https://github.com/clj-commons/aleph/issues/238>
          ;; (aleph.netty/wait-for-close server)
          {:port    (aleph.netty/port server)
           :stop-fn (fn [] (.close ^java.io.Closeable server) (deliver p nil))})

        uri (format "http://localhost:%s/" port)]

    (tel/log! (str "HTTP server is running at: " uri))
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch Exception _))

    (reset! web-server_ {:port port, :stop-fn stop-fn})))

(defn stop!  [] (stop-router!) (stop-web-server!))
(defn start! [& [port]]
  (tel/log! (str "Sente version: " sente/sente-version))
  (tel/log! (str "Min log level: " @min-log-level_))
  (start-router!)
  (let [stop-fn (start-web-server! port)]
    @auto-loop_
    stop-fn))

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!) ; Eval this at REPL to start server via REPL
  (test-broadcast!)

  (broadcast! [:example/foo])
  @connected-uids_
  @conns_

  ;; Restart at same port
  (do (stop!) (start! (:port @web-server_))))
