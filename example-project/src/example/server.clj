(ns example.server
  "Official Sente reference example: server"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults]
   [ring.middleware.anti-forgery :as anti-forgery]
   [compojure.core     :as comp :refer [defroutes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer [<! <!! >! >!! put! chan go go-loop]]
   [taoensso.encore    :as encore :refer [have have?]]
   [taoensso.timbre    :as timbre]
   [taoensso.sente     :as sente]

   ;;; TODO Choose (uncomment) a supported web server + adapter -------------
   ;;[org.httpkit.server :as http-kit]
   ;;[taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]

   ;; [immutant.web :as immutant]
   ;; [taoensso.sente.server-adapters.immutant :refer [get-sch-adapter]]

   ;; [nginx.clojure.embed :as nginx-clojure]
   ;; [taoensso.sente.server-adapters.nginx-clojure :refer [get-sch-adapter]]

   ;; [aleph.http :as aleph]
   ;; [taoensso.sente.server-adapters.aleph :refer [get-sch-adapter]]

   [ring.adapter.jetty9 :as jetty]
   [taoensso.sente.server-adapters.jetty9 :refer [get-sch-adapter]]
   ;;
   ;; See https://gist.github.com/wavejumper/40c4cbb21d67e4415e20685710b68ea0
   ;; for full example using Jetty 9

   ;; -----------------------------------------------------------------------

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit]))

;;;; Logging config

(defonce min-log-level_ (atom nil))

(defn- set-min-log-level! [level]
  (sente/set-min-log-level! level) ; Min log level for internal Sente namespaces
  (timbre/set-ns-min-level! level) ; Min log level for this           namespace
  (reset! min-log-level_    level))

(set-min-log-level! :info)

;;;; Define our Sente channel socket (chsk) server

(let [;; Serialization format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep
      ]

  (defonce chsk-server
    (sente/make-channel-socket-server!
      (get-sch-adapter) {:packer packer})))

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]

  (defonce ring-ajax-post                ajax-post-fn)
  (defonce ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (defonce ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (defonce chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (defonce connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; We can watch this atom for changes if we like
(add-watch connected-uids :connected-uids
  (fn [_ _ old new]
    (when (not= old new)
      (timbre/infof "Connected uids change: %s" new))))

;;;; Ring handlers

(defn landing-pg-handler [ring-req]
  (hiccup/html
    (let [csrf-token
          ;; (:anti-forgery-token ring-req) ; Also an option
          (force anti-forgery/*anti-forgery-token*)]
      [:div#sente-csrf-token {:data-csrf-token csrf-token}])

    [:h3 "Sente reference example"]
    [:p
     "For this example, a " [:i "random"] " " [:strong [:code ":ajax/:auto"]]
     " connection mode has been selected (see " [:strong "client output"] ")."
     [:br]
     "To " [:strong "re-randomize"] ", hit your browser's reload/refresh button."]
    [:ul
     [:li [:strong "Server output:"] " → " [:code "*std-out*"]]
     [:li [:strong "Client output:"] " → Below textarea and/or browser console"]]
    [:textarea#output {:style "width: 100%; height: 200px;" :wrap "off"}]

    [:h4 "Controls"]
    [:p
     [:button#btn1 {:type "button"} "chsk-send! (with reply)"] " "
     [:button#btn2 {:type "button"} "chsk-send! (w/o reply)"] " "]
    [:p
     [:button#btn3 {:type "button"} "Rapid server>user async push"] " "
     [:button#btn4 {:type "button"} "Toggle server>user async broadcast push loop"]]
    [:p
     [:button#btn5 {:type "button"} "Disconnect"] " "
     [:button#btn6 {:type "button"} "Reconnect"] " "
     [:button#btn7 {:type "button"} "Simulate break (with on-close)"] " "
     [:button#btn8 {:type "button"} "Simulate break (w/o on-close)"]]
    [:p [:button#btn9 {:type "button"} "Toggle min log level"]]

    [:p "Log in with a " [:strong "user-id"] " below so that the server can directly address this user-id's connected clients:"]
    [:p
     [:input#input-login {:type :text :placeholder "User-id"}] " "
     [:button#btn-login {:type "button"} "← Log in with user-id"]]
    [:script {:src "main.js"}] ; Include our cljs target
    ))

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (timbre/debugf "Login request: %s" params)
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

(defn test-fast-server>user-pushes
  "Quickly pushes 100 events to all connected users. Note that this'll be
  fast+reliable even over Ajax!"
  []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

(defonce broadcast-enabled?_ (atom true))

(defn start-example-broadcaster!
  "As an example of server>user async pushes, setup a loop to broadcast an
  event to all connected users every 10 seconds"
  []
  (let [broadcast!
        (fn [i]
          (let [uids (:any @connected-uids)]
            (timbre/debugf "Broadcasting server>user: %s uids" (count uids))
            (doseq [uid uids]
              (chsk-send! uid
                [:some/broadcast
                 {:what-is-this "An async broadcast pushed from server"
                  :how-often "Every 10 seconds"
                  :to-whom uid
                  :i i}]))))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (when @broadcast-enabled?_ (broadcast! i))
      (recur (inc i)))))

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
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))

(defmethod -event-msg-handler :example/test-rapid-push
  [ev-msg] (test-fast-server>user-pushes))

(defmethod -event-msg-handler :example/toggle-broadcast
  [{:as ev-msg :keys [?reply-fn]}]
  (let [loop-enabled? (swap! broadcast-enabled?_ not)]
    (?reply-fn loop-enabled?)))

(defmethod -event-msg-handler :example/toggle-min-log-level
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

(defonce    web-server_ (atom nil)) ; (fn stop [])
(defn  stop-web-server! [] (when-let [stop-fn @web-server_] (stop-fn)))
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [port (or port 3333) ; 0 => Choose any available port
        ring-handler (var main-ring-handler)

        [port stop-fn]
        ;;; TODO Choose (uncomment) a supported web server ------------------
        ;;(let [stop-fn (http-kit/run-server ring-handler {:port port})]
        ;;  [(:local-port (meta stop-fn)) (fn [] (stop-fn :timeout 100))]]
        ;;
        ;; (let [server (immutant/run ring-handler :port port)]
        ;;   [(:port server) (fn [] (immutant/stop server))])
        ;;
        ;; (let [port (nginx-clojure/run-server ring-handler {:port port})]
        ;;   [port (fn [] (nginx-clojure/stop-server))])
        ;;
        ;; (let [server (aleph/start-server ring-handler {:port port})
        ;;       p (promise)]
        ;;   (future @p) ; Workaround for Ref. https://goo.gl/kLvced
        ;;   ;; (aleph.netty/wait-for-close server)
        ;;   [(aleph.netty/port server)
        ;;    (fn [] (.close ^java.io.Closeable server) (deliver p nil))])

        (let [#_#_ws-handshake (:ajax-get-or-ws-handshake-fn (sente/make-channel-socket! (get-sch-adapter)))
              server (jetty/run-jetty ring-handler {:port port
                                                    :async? true
                                                    :join? false
                                                    :websockets {"/chsk" ws-handshake}})]
          [port (fn [] (jetty/stop-server server))])
        ;; ------------------------------------------------------------------

        uri (format "http://localhost:%s/" port)]

    (timbre/infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))

    (reset! web-server_ stop-fn)))

(defn stop!  []  (stop-router!)  (stop-web-server!))
(defn start! [] (start-router!) (start-web-server!) (start-example-broadcaster!))

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!) ; Eval this at REPL to start server via REPL
  (test-fast-server>user-pushes)
  (stop!))
