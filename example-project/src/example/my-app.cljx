(ns example.my-app
  "Simple Sente client+server web app example.
  Uses Kevin Lynagh's awesome Cljx Leiningen plugin,
  Ref. https://github.com/lynaghk/cljx

  INSTRUCTIONS:
    1. Call `lein start-dev` at your terminal.
    2. Connect to development nREPL (port will be printed).
    3. Evaluate this namespace.
    4. Open browser & point to local http server (port will be printed).
    5. Observe browser's console + nREPL's std-out."
  {:author "Peter Taoussanis"}

  #+clj
  (:require
   [compojure.core     :as comp :refer (defroutes routes GET POST)]
   [compojure.route    :as route]
   [compojure.handler  :as comp-handler]
   [hiccup.core        :as hiccup]
   [org.httpkit.server :as http-kit-server]
   [clojure.core.match :as match :refer (match)]
   [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
   [taoensso.timbre    :as timbre]
   [taoensso.sente     :as sente]
   [ring.middleware.anti-forgery :as ring-anti-forgery])

  #+cljs
  (:require-macros
   [cljs.core.match.macros :refer (match)]
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  #+cljs
  (:require
   [cljs.core.match]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer (logf)]
   [taoensso.sente  :as sente  :refer (cb-success?)]))

;; #+clj (timbre/set-level! :trace)

;;;; Setup server-side chsk handlers -------------------------------------------

#+clj
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv)
  (def chsk-send!                    send-fn))

#+clj
(defn landing-pg-handler [req]
  (let [;; A unique user id should be sessionized under :uid key during login -
        ;; this could be a username, uuid, etc.
        uid (or (-> req :session :uid) (rand-int 99))]

    {:status 200
     :session (assoc (:session req) :uid uid)
     :body
     (hiccup/html
      [:h1 "This is my landing page, yo!"]
      [:p [:small (format "Session: %s" (:session req))]]
      [:p [:small (format "Random user id (`:uid` key in Ring session): %s" uid)]]
      [:hr]
      [:p [:strong "Step 1: "] "Ensure your browser's JavaScript console is open."]
      [:p [:strong "Step 2: "] "Try the buttons: "
       [:button#btn1 {:type "button"} "chsk-send! (w/o reply)"]
       [:button#btn2 {:type "button"} "chsk-send! (with reply)"]]

      [:p [:strong "Step 3: "] "Observe browser's console + nREPL's std-out." ]

      ;;; Communicate releavnt state to client (you could do this any way that's
      ;;; convenient, just keep in mind that client state is easily forged):
      [:script (format "var csrf_token='%s';" ring-anti-forgery/*anti-forgery-token*)]
      [:script (format "var has_uid=%s;" (if uid "true" "false"))]
      ;;
      [:script {:src "main.js"}] ; Include our cljs target
      )}))

#+clj
(defroutes my-ring-handler
  (->
   (routes
    (GET  "/"     req (landing-pg-handler req))
    ;;
    (GET  "/chsk" req (#'ring-ajax-get-or-ws-handshake req)) ; Note the #'
    (POST "/chsk" req (#'ring-ajax-post                req)) ; ''
    ;;
    (route/resources "/") ; Static files, notably public/main.js (our cljs target)
    (route/not-found "<h1>Page not found</h1>"))

   ;;; Middleware

   ;; Sente adds a :csrf-token param to Ajax requests:
   (ring-anti-forgery/wrap-anti-forgery
    {:read-token (fn [req] (-> req :params :csrf-token))})

   compojure.handler/site))

#+clj (defn- logf [fmt & xs] (println (apply format fmt xs)))

#+clj
(defonce http-server ; Runs once, on first eval
  (let [s (http-kit-server/run-server #'my-ring-handler {:port 0})] ; Note the #'
    (logf
     (str "Http-kit server is running on `http://localhost:%s/` "
          "(it should be browser-accessible now).")
     (:local-port (meta s)))))

;;;; Setup client-side chsk handlers -------------------------------------------

#+cljs (def csrf-token        (aget js/window "csrf_token"))
#+cljs (def has-uid?   (true? (aget js/window "has_uid")))
#+cljs
(let [{:keys [chsk ch-recv send-fn]}
      (sente/make-channel-socket! "/chsk" ; Note the same URL as before
       {:csrf-token csrf-token
        :has-uid?   has-uid?}
       {:type :auto #_:ajax ; e/o #{:auto :ajax; :ws}
        })]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))

;;;; Setup routers -------------------------------------------------------------

#+cljs (logf "ClojureScript appears to have loaded correctly.")
#+cljs (logf "CSRF token from server: %s" csrf-token)

#+clj
(defn- event-msg-handler
  [{:as ev-msg :keys [ring-req event ?reply-fn]} _]
  (let [session (:session ring-req)
        uid     (:uid session)
        [id data :as ev] event]

    (logf "Event: %s" ev)
    (match [id data]
    ;; TODO: Match your events here, reply when appropriate <...>
    :else
    (do (logf "Unmatched event: %s" ev)
        (when-not (:dummy-reply-fn? (meta ?reply-fn))
          (?reply-fn {:umatched-event-as-echoed-from-from-server ev}))))))

#+clj
(defonce chsk-router
  (sente/start-chsk-router-loop! event-msg-handler ch-chsk))

#+cljs
(defn- event-handler [[id data :as ev] _]
  (logf "Event: %s" ev)
  (match [id data]
    ;; TODO Match your events here <...>
    [:chsk/state [:first-open _]] (logf "Channel socket successfully established!")
    [:chsk/state new-state] (logf "Chsk state change: %s" new-state)
    [:chsk/recv  payload]   (logf "Push event from server: %s" payload)
    :else (logf "Unmatched event: %s" ev)))

#+cljs
(defonce chsk-router
  (sente/start-chsk-router-loop! event-handler ch-chsk))

;;;; Example: broadcast server>clientS

;; As an example of push notifications, we'll setup a server loop to broadcast
;; an event to _all_ possible user-ids every 10 seconds:
#+clj
(defonce broadcaster
  (go-loop [i 0]
    (<! (async/timeout 10000))
    (doseq [uid (range 100)]
      (chsk-send! uid
        [:some/broadcast
         {:what-is-this "A broadcast pushed from server"
          :how-often    "Every 10 seconds"
          :to-whom uid
          :i i}]))
    (recur (inc i))))

;;;; Setup client buttons

#+cljs
(.addEventListener (.getElementById js/document "btn1") "click"
  (fn [ev]
    (logf "Button 1 was clicked (won't receive any reply from server)")
    (chsk-send! [:example/button1 {:had-a-callback? "nope"}])))

#+cljs
(.addEventListener (.getElementById js/document "btn2") "click"
  (fn [ev]
    (logf "Button 2 was clicked (will receive reply from server)")
    (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
      (fn [cb-reply] (logf "Callback reply: %s" cb-reply)))))
