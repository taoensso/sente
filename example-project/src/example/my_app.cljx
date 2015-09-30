(ns example.my-app
  "Sente client+server reference web-app example.
  Uses Kevin Lynagh's awesome Cljx Leiningen plugin,
  Ref. https://github.com/lynaghk/cljx

  ------------------------------------------------------------------------------
  This example dives into Sente's full functionality quickly; it's probably
  more useful as a reference than a tutorial. See the GitHub README for a
  gentler intro.
  ------------------------------------------------------------------------------

  Instructions:
    1. Call `lein start-dev` at your terminal.
    2. Connect to development nREPL (port will be printed).
    3. Evaluate this namespace (M-x `cider-load-current-buffer` for CIDER+Emacs).
    4. Evaluate `(start!)` in this namespace (M-x `cider-eval-last-sexp` for
       CIDER+Emacs).
    5. Open browser & point to local web server (port will be printed).
    6. Observe browser's console + nREPL's std-out.

  Light Table users:
    To configure Cljx support please see Ref. http://goo.gl/fKL5Z4."

  {:author "Peter Taoussanis, and contributors"}

  #+clj
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
   [taoensso.sente.packers.transit :as sente-transit])

  #+cljs
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer ()]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente  :as sente  :refer (cb-success?)]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit])

  #+cljs
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;;;; Logging config

;; (sente/set-logging-level! :trace) ; Uncomment for more logging

;;;; ---> Choose (uncomment) a supported web server and adapter <---

#+clj
(defn start-web-server!* [ring-handler port]
  (println "Starting http-kit...")
  (let [http-kit-stop-fn (http-kit/run-server ring-handler {:port port})]
    {:server  nil ; http-kit doesn't expose this
     :port    (:local-port (meta http-kit-stop-fn))
     :stop-fn (fn [] (http-kit-stop-fn :timeout 100))}))

;; #+clj
;; (defn start-web-server!* [ring-handler port]
;;   (println "Starting Immutant...")
;;   (let [server (immutant/run ring-handler :port port)]
;;     {:server  server
;;      :port    (:port server)
;;      :stop-fn (fn [] (immutant/stop server))}))

;; #+clj
;; (defn start-web-server!* [ring-handler port]
;;   (println "Starting nginx-clojure...")
;;   (let [port (nginx-clojure/run-server ring-handler {:port port})]
;;     {:server  nil ; nginx-clojure doesn't expose this
;;      :port    port
;;      :stop-fn nginx-clojure/stop-server}))

;;;; Packer (client<->server serializtion format) config

(def packer (sente-transit/get-flexi-packer :edn)) ; Experimental, needs Transit dep
;; (def packer :edn) ; Default packer (no need for Transit dep)

;;;; Server-side setup

#+clj
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket-server! sente-web-server-adapter {:packer packer})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

#+clj
(defn landing-pg-handler [req]
  (hiccup/html
    [:h1 "Sente reference example"]
    [:p "An Ajax/WebSocket connection has been configured (random)."]
    [:hr]
    [:p [:strong "Step 1: "] "Open browser's JavaScript console."]
    [:p [:strong "Step 2: "] "Try: "
     [:button#btn1 {:type "button"} "chsk-send! (w/o reply)"]
     [:button#btn2 {:type "button"} "chsk-send! (with reply)"]]
    ;;
    [:p [:strong "Step 3: "] "See browser's console + nREPL's std-out." ]
    ;;
    [:hr]
    [:h2 "Login with a user-id"]
    [:p  "The server can use this id to send events to *you* specifically."]
    [:p [:input#input-login {:type :text :placeholder "User-id"}]
        [:button#btn-login {:type "button"} "Secure login!"]]
    [:script {:src "main.js"}] ; Include our cljs target
    ))

#+clj
(defn login!
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-request]
  (let [{:keys [session params]} ring-request
        {:keys [user-id]} params]
    (debugf "Login request: %s" params)
    {:status 200 :session (assoc session :uid user-id)}))

#+clj
(defroutes my-routes
  (GET  "/"      req (landing-pg-handler req))
  ;;
  (GET  "/chsk"  req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk"  req (ring-ajax-post                req))
  (POST "/login" req (login! req))
  ;;
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

#+clj
(def my-ring-handler
  (let [ring-defaults-config
        (assoc-in ring.middleware.defaults/site-defaults [:security :anti-forgery]
                  {:read-token (fn [req] (-> req :params :csrf-token))})]

    ;; NB: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
    ;; middleware to work. These are included with
    ;; `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
    ;; that they're included yourself if you're not using `wrap-defaults`.
    ;;
    (ring.middleware.defaults/wrap-defaults my-routes ring-defaults-config)))

;;;; Client-side setup

#+cljs (debugf "ClojureScript appears to have loaded correctly.")
#+cljs
(let [rand-chsk-type (if (>= (rand) 0.5) :ajax :auto)

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client! "/chsk" ; Note the same URL as before
        {:type   rand-chsk-type
         :packer packer})]
  (debugf "Randomly selected chsk type: %s" rand-chsk-type)
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
  (debugf "Event: %s" event)
  (event-msg-handler ev-msg))

#+clj
(do ; Server-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [session (:session ring-req)
          uid     (:uid     session)]
      (debugf "Unhandled event: %s" event)
      (when ?reply-fn
        (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

  ;; Add your (defmethod event-msg-handler <event-id> [ev-msg] <body>)s here...
  )

#+cljs
(do ; Client-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event]}]
    (debugf "Unhandled event: %s" event))

  (defmethod event-msg-handler :chsk/state
    [{:as ev-msg :keys [?data]}]
    (if (= ?data {:first-open? true})
      (debugf "Channel socket successfully established!")
      (debugf "Channel socket state change: %s" ?data)))

  (defmethod event-msg-handler :chsk/recv
    [{:as ev-msg :keys [?data]}]
    (debugf "Push event from server: %s" ?data))

  (defmethod event-msg-handler :chsk/handshake
    [{:as ev-msg :keys [?data]}]
    (let [[?uid ?csrf-token ?handshake-data] ?data]
      (debugf "Handshake: %s" ?data)))

  ;; Add your (defmethod handle-event-msg! <event-id> [ev-msg] <body>)s here...
  )

;;;; Client-side UI

#+cljs
(when-let [target-el (.getElementById js/document "btn1")]
  (.addEventListener target-el "click"
    (fn [ev]
      (debugf "Button 1 was clicked (won't receive any reply from server)")
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

#+cljs
(when-let [target-el (.getElementById js/document "btn2")]
  (.addEventListener target-el "click"
    (fn [ev]
      (debugf "Button 2 was clicked (will receive reply from server)")
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply] (debugf "Callback reply: %s" cb-reply))))))

#+cljs
(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (debugf "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.

            (sente/ajax-call "/login"
              {:method :post
               :params {:user-id    (str user-id)
                        :csrf-token (:csrf-token @chsk-state)}}
              (fn [ajax-resp]
                (debugf "Ajax login response: %s" ajax-resp)
                (let [login-successful? true ; Your logic here
                      ]
                  (if-not login-successful?
                    (debugf "Login failed")
                    (do
                      (debugf "Login successful")
                      (sente/chsk-reconnect! chsk))))))))))))

;;;; Example: broadcast server>user

;; As an example of push notifications, we'll setup a server loop to broadcast
;; an event to _all_ possible user-ids every 10 seconds:
#+clj
(defn start-broadcaster! []
  (go-loop [i 0]
    (<! (async/timeout 10000))
    (println (format "Broadcasting server>user: %s" @connected-uids))
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid
        [:some/broadcast
         {:what-is-this "A broadcast pushed from server"
          :how-often    "Every 10 seconds"
          :to-whom uid
          :i i}]))
    (recur (inc i))))

#+clj ; Note that this'll be fast+reliable even over Ajax!:
(defn test-fast-server>user-pushes []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

;;;; Init

#+clj (defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}
#+clj (defn stop-web-server! [] (when-let [m @web-server_] ((:stop-fn m))))
#+clj
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-web-server!* (var my-ring-handler)
          (or port 0) ; 0 => auto (any available) port
          )
        uri (format "http://localhost:%s/" port)]
    (debugf "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))
    (reset! web-server_ server-map)))

#+clj  (defonce router_ (atom nil))
#+cljs (def     router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! [server?]
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler* server?)))

(defn start! []
  #+clj (start-router! true)
  #+cljs (start-router! false)
  #+clj (start-web-server!)
  #+clj (start-broadcaster!))

#+clj (defn -main [] (start!)) ; For `lein run`, etc.

#+cljs   (start!)
;; #+clj (start!) ; Server-side auto-start disabled for LightTable, etc.
(comment (start!)
         (test-fast-server>user-pushes))
