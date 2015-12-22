(ns example.my-app-node
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

  (:require-macros [hiccups.core :as hiccups])
  (:require
   [cljs.nodejs :as nodejs]
   [clojure.string     :as str]
   [cljs.core.async :as async  :refer (<!)]
   [taoensso.encore    :as encore :refer ()]
   [taoensso.timbre    :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente     :as sente]
   [hiccups.runtime]

   ;;; ---> Choose (uncomment) a supported web server and adapter <---

   ;; Dogfort
   ;; [dogfort.middleware.defaults :as defaults]
   ;; [dogfort.middleware.routes]
   ;; [taoensso.sente.server-adapters.dogfort :refer (sente-web-server-adapter)]
   ;; [dogfort.http :refer (run-http)]

   ;; Express
   [taoensso.sente.server-adapters.express :as sente-express]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit])

  (:require-macros
   [dogfort.middleware.routes-macros :refer (defroutes GET POST)]
   [hiccups.core :as hiccups]
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(enable-console-print!)
(timbre/set-level! :trace)

;;;; Packer (client<->server serializtion format) config

(def packer (sente-transit/get-flexi-packer :edn)) ; Experimental, needs Transit dep
;; (def packer :edn) ; Default packer (no need for Transit dep)

;;;; Server-side setup

(defn landing-pg-handler [req]
  (hiccups/html
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



;; ---> UNCOMMENT FROM HERE FOR DOGFORT <---

;; (let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
;;               connected-uids]}
;;       (sente/make-channel-socket-server! sente-web-server-adapter {:packer packer})]
;;   (def ring-ajax-post                ajax-post-fn)
;;   (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
;;   (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
;;   (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
;;   (def connected-uids                connected-uids) ; Watchable, read-only atom
;;   )

;; (defn start-web-server!* [ring-handler port]
;;   (println "Starting dogfort...")
;;   (run-http ring-handler {:port port}))

;; (defn login!
;;   "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
;;   In our simplified example we'll just always successfully authenticate the user
;;   with whatever user-id they provided in the auth request."
;;   [ring-request]
;;   (let [{:keys [session params]} ring-request
;;         {:keys [user-id]} params]
;;     (debugf "Login request: %s" params)
;;     {:status 200 :session (assoc session :uid user-id)}))


;; (defroutes my-routes
;;   (GET  "/"      req (landing-pg-handler req))
;;   ;;
;;   (GET  "/chsk"  req (ring-ajax-get-or-ws-handshake req))
;;   (POST "/chsk"  req (ring-ajax-post                req))
;;   (POST "/login" req (login! req)))

;; (def my-ring-handler
;;   (defaults/wrap-defaults my-routes {:wrap-file "resources/public"}))

;; ---> UNCOMMENT TO HERE FOR DOGFORT <---


;; ---> UNCOMMENT FROM HERE FOR EXPRESS <---

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente-express/make-channel-socket-server! {:packer packer})]
  (def express-ajax-post                ajax-post-fn)
  (def express-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; express doesn't use it, but the example program does.
(def my-ring-handler nil)

(def http       (nodejs/require "http"))
(def express (nodejs/require "express"))
(def express-ws (nodejs/require "express-ws"))
(def ws (nodejs/require "ws"))
(def cookie-parser (nodejs/require "cookie-parser"))
(def body-parser (nodejs/require "body-parser"))
(def csurf (nodejs/require "csurf"))
(def session (nodejs/require "express-session"))

(def web-app (atom nil))


(defn get-csrf-token
  "Sente uses 'csrf-token' in it's request but csurf usually uses
  '_csrf'.  This extends what csurf looks for."
  [req]
  (let [body (.-body req)
        query (.-query req)
        headers (.-headers req)]
    ;; It would be nice to be able to tell sente to use a different
    ;; name...
    (or
     (aget body "_csrf") (aget body "csrf-token")
     (aget query "_csrf") (aget query "csrf-token")
     (aget headers "csrf-token") (aget headers "xsrf-token")
     (aget headers "x-csrf-token") (aget headers "x-xsrf-token"))))

(def cookie-secret "the shiz")

(def session-config #js {:secret cookie-secret
                         :resave true
                         :cookie {}
                         :store (.MemoryStore session)
                         :saveUninitialized true})

(def csurf-config #js {:cookie false
                       :value get-csrf-token})

(defn login!
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [req res]
  (let [req-session (aget req "session")
        params (aget req "params")
        user-id (aget params "user-id")]
    (debugf "Login request: %s" params)
    (aset req-session "uid" user-id)
    (.send res "Success")))

(defn start-web-server!* [ring-handler port]
  (println "Starting express...")
  (let [app (express)
        wss (express-ws app)]
    (reset! web-app {:app app :wss wss})

    (doto app
      (.use (fn [req res next]
              (tracef "Request: %s" (.-originalUrl req))
              (next)))
      (.use (session session-config))
      (.use (.urlencoded body-parser #js {:extended false}))
      (.use (cookie-parser cookie-secret))
      (.use (csurf csurf-config))

      (.get "/" (fn [req res] (.send res (landing-pg-handler req))))

      (.ws "/chsk"
           (fn [ws req next]
             (express-ajax-get-or-ws-handshake req nil nil {:websocket? true
                                                            :websocket ws})))
      (.use (fn [req res next]
              (tracef "Not websocket: %s" (.-originalUrl req))
              (next)))
      (.get "/chsk" express-ajax-get-or-ws-handshake)
      (.post "/chsk" express-ajax-post)
      (.post "/login" login!)

      (.use (.static express "resources/public"))
      (.use (fn [req res next]
              (tracef "Unhandled request: %s" (.-originalUrl req))
              (next)))
      (.listen  port))))

;; ;; ---> UNCOMMENT TO HERE FOR EXPRESS <---

;;;; Routing handlers

;; So you'll want to define one server-side and one client-side
;; (fn event-msg-handler [ev-msg]) to correctly handle incoming events. How you
;; actually do this is entirely up to you. In this example we use a multimethod
;; that dispatches to a method based on the `event-msg`'s event-id. Some
;; alternatives include a simple `case`/`cond`/`condp` against event-ids, or
;; `core.match` against events.

(defmulti event-msg-handler :id) ; Dispatch on event-

;; Wrap for logging, catching, etc.:
(defn     event-msg-handler* [{:as ev-msg :keys [id ?data event]}]
  (debugf "Event: %s" event)
  (event-msg-handler ev-msg))


(do ; Server-side methods
  (defmethod event-msg-handler :default ; Fallback
    [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
    (let [session (:session ring-req)
          uid     (:uid     session)]
      (prn "Unhandled event:" event)
      (when ?reply-fn
        (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

  ;; Add your (defmethod event-msg-handler <event-id> [ev-msg] <body>)s here...
  )

;;;; Example: broadcast server>user

;; As an example of push notifications, we'll setup a server loop to broadcast
;; an event to _all_ possible user-ids every 10 seconds:

(defn start-broadcaster! []
  (go-loop [i 0]
           (<! (async/timeout 10000))
           (println (str "Broadcasting server>user: " @connected-uids))
           (doseq [uid (:any @connected-uids)]
             (chsk-send! uid
                         [:some/broadcast
                          {:what-is-this "A broadcast pushed from server"
                           :how-often    "Every 10 seconds"
                           :to-whom uid
                           :i i}]))
           (recur (inc i))))

; Note that this'll be fast+reliable even over Ajax!:
(defn test-fast-server>user-pushes []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

;;;; Init

(defonce web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}
;(defn stop-web-server! [] (when-let [m @web-server_] ((:stop-fn m))))

(defn start-web-server! []
  (start-web-server!* my-ring-handler 5000)
  (debugf "Web server is at %s" "http://localhost:5000"))

(defonce router_ (atom nil))

(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler* true)))

(defn start! []
  (start-router!)
  (start-web-server!)
  (start-broadcaster!))

(defn -main [] (start!)) ; For `lein run`, etc.


;; #+clj (start!) ; Server-side auto-start disabled for LightTable, etc.
(comment (start!)
  (test-fast-server>user-pushes))

(set! *main-cli-fn* start!)

;;;;;;;;;;;; This file autogenerated from src/example/my_app.cljx
