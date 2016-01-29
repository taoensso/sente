(ns example.server
  "Sente client+server reference example
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

      ;;; ---> Choose (uncomment) a supported web server and adapter <---
      [org.httpkit.server :as http-kit]
      [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
      ;;
      ;; [immutant.web :as immutant]
      ;; [taoensso.sente.server-adapters.immutant :refer (sente-web-server-adapter)]
      ;;
      ;; [nginx.clojure.embed :as nginx-clojure]
      ;; [taoensso.sente.server-adapters.nginx-clojure :refer (sente-web-server-adapter)]

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

;;;; ---> Choose (uncomment) a supported web server and adapter <---

#?(:clj
(defn start-web-server!* [ring-handler port]
  (infof "Starting http-kit...")
  (let [http-kit-stop-fn (http-kit/run-server ring-handler {:port port})]
    {:server  nil ; http-kit doesn't expose this
     :port    (:local-port (meta http-kit-stop-fn))
     :stop-fn (fn [] (http-kit-stop-fn :timeout 100))})))

;; #?(:clj
;; (defn start-web-server!* [ring-handler port]
;;   (infof "Starting Immutant...")
;;   (let [server (immutant/run ring-handler :port port)]
;;     {:server  server
;;      :port    (:port server)
;;      :stop-fn (fn [] (immutant/stop server))})))

;; #?(:clj
;; (defn start-web-server!* [ring-handler port]
;;   (infof "Starting nginx-clojure...")
;;   (let [port (nginx-clojure/run-server ring-handler {:port port})]
;;     {:server  nil ; nginx-clojure doesn't expose this
;;      :port    port
;;      :stop-fn nginx-clojure/stop-server})))

;;;; Packer (client<->server serializtion format) config

(def packer
  :edn ; Default packer, a good choice in most cases
  ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit dep
  )

;;;; Server-side setup

#?(:clj
   (let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
                 connected-uids]}
         (sente/make-channel-socket! sente-web-server-adapter {:packer packer})]
     (def ring-ajax-post                ajax-post-fn)
     (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
     (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
     (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
     (def connected-uids                connected-uids) ; Watchable, read-only atom
     ))

#?(:clj
   (defn landing-pg-handler [req]
     (hiccup/html
      [:h1 "Sente reference example"]
      [:p "An Ajax/WebSocket" [:strong " (random choice!)"] " has been configured for this example"]
      [:hr]
      [:p [:strong "Step 1: "] " try hitting the buttons:"]
      [:button#btn1 {:type "button"} "chsk-send! (w/o reply)"]
      [:button#btn2 {:type "button"} "chsk-send! (with reply)"]
      ;;
      [:p [:strong "Step 2: "] " observe std-out (for server output) and below (for client output):"]
      [:textarea#output {:style "width: 100%; height: 200px;"}]
      ;;
      [:hr]
      [:h2 "Step 3: try login with a user-id"]
      [:p  "The server can use this id to send events to *you* specifically."]
      [:p
       [:input#input-login {:type :text :placeholder "User-id"}]
       [:button#btn-login {:type "button"} "Secure login!"]]
      ;;
      [:hr]
      [:h2 "Step 4: want to re-randomize Ajax/WebSocket connection type?"]
      [:p "Hit your browser's reload/refresh button"]
      [:script {:src "main.js"}] ; Include our cljs target
      )))

#?(:clj
   (defn login!
     "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
     [ring-request]
     (let [{:keys [session params]} ring-request
           {:keys [user-id]} params]
       (debugf "Login request: %s" params)
       {:status 200 :session (assoc session :uid user-id)})))

#?(:clj
(defroutes my-routes
  (GET  "/"      req (landing-pg-handler req))
  ;;
  (GET  "/chsk"  req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk"  req (ring-ajax-post                req))
  (POST "/login" req (login! req))
  ;;
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>")))

#?(:clj
(def my-ring-handler
  (let [ring-defaults-config
        (assoc-in ring.middleware.defaults/site-defaults [:security :anti-forgery]
          {:read-token (fn [req] (-> req :params :csrf-token))})]

    ;; NB: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
    ;; middleware to work. These are included with
    ;; `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
    ;; that they're included yourself if you're not using `wrap-defaults`.
    ;;
    (ring.middleware.defaults/wrap-defaults my-routes ring-defaults-config))))

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

; Server-side methods
(defmethod event-msg-handler :default ; Fallback
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

;; Add your (defmethod event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;; Example: broadcast server>user

;; As an example of push notifications, we'll setup a server loop to broadcast
;; an event to _all_ possible user-ids every 10 seconds:
(defn start-broadcaster! []
  (let [broadcast!
        (fn [i]
          (debugf "Broadcasting server>user: %s" @connected-uids)
          (doseq [uid (:any @connected-uids)]
            (chsk-send! uid
              [:some/broadcast
               {:what-is-this "An async broadcast pushed from server"
                :how-often "Every 10 seconds"
                :to-whom uid
                :i i}])))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (broadcast! i)
      (recur (inc 1)))))

;; Note that this'll be fast+reliable even over Ajax!:
(defn test-fast-server>user-pushes []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

;;;; Init

(defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}
(defn stop-web-server! [] (when-let [m @web-server_] ((:stop-fn m))))
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-web-server!* (var my-ring-handler)
          (or port 0) ; 0 => auto (any available) port
          )
        uri (format "http://localhost:%s/" port)]
    (debugf "Web server is running at `%s`" uri)
    #?(:clj
       (try
         (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
         (catch java.awt.HeadlessException _)))
    (reset! web-server_ server-map)))

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defn start! []
  (start-router!)
  #?(:clj
     (do (start-web-server!)
         (start-broadcaster!))))

;; #?(:clj (defonce _start-once (start!)))

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!)
  (test-fast-server>user-pushes))
