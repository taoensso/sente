(ns example.my-app
  "Sente client+server reference web-app example.
  Uses Kevin Lynagh's awesome Cljx Leiningen plugin,
  Ref. https://github.com/lynaghk/cljx

  ------------------------------------------------------------------------------
  This example dives into Sente's full functionality quite quickly and is thus
  probably more useful as a reference than a tutorial. See the GitHub README for
  a somewhat gentler intro.
  ------------------------------------------------------------------------------

  INSTRUCTIONS:
    1. Call `lein start-dev` at your terminal.
    2. Connect to development nREPL (port will be printed).
    3. Evaluate this namespace and `(start-http-server!)` in this namespace.
    4. Open browser & point to local http server (port will be printed).
    5. Observe browser's console + nREPL's std-out.

  LIGHT TABLE USERS:
    To configure Cljx support please see Ref. http://goo.gl/fKL5Z4."
  {:author "Peter Taoussanis"}

  #+clj
  (:require
   [clojure.string     :as str]
   [compojure.core     :as comp :refer (defroutes GET POST)]
   [compojure.route    :as route]
   [ring.middleware.defaults]
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
   [clojure.string  :as str]
   [cljs.core.match]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as encore :refer (logf)]
   [taoensso.sente  :as sente  :refer (cb-success?)]))

;; #+clj (timbre/set-level! :trace)
#+clj (defn- logf [fmt & xs] (println (apply format fmt xs)))

;;;; Setup server-side chsk handlers -------------------------------------------

#+clj
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! {})]
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
    (logf "Login request: %s" params)
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
   (ring.middleware.defaults/wrap-defaults my-routes ring-defaults-config)))

#+clj (defonce http-server_ (atom nil))

#+clj
(defn stop-http-server! []
  (when-let [current-server @http-server_]
    (current-server :timeout 100)))

#+clj
(defn start-http-server! []
  (let [s   (http-kit-server/run-server (var my-ring-handler) {:port 0})
        uri (format "http://localhost:%s/" (:local-port (meta s)))]
    (stop-http-server!)
    (logf "Http-kit server is running at `%s`" uri)
    (.browse (java.awt.Desktop/getDesktop)
             (java.net.URI. uri))
    (reset! http-server_ s)))

(comment (start-http-server!))

;;;; Setup client-side chsk handlers -------------------------------------------

#+cljs
(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same URL as before
        {:type (if (>= (rand) 0.5) :ajax :auto)})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state) ; Watchable, read-only atom
  )

;;;; Setup routers -------------------------------------------------------------

#+cljs (logf "ClojureScript appears to have loaded correctly.")
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
    [:chsk/state {:first-open? true}]
    (logf "Channel socket successfully established!")
    [:chsk/state new-state] (logf "Chsk state change: %s" new-state)
    [:chsk/recv  payload]   (logf "Push event from server: %s" payload)
    :else (logf "Unmatched event: %s" ev)))

#+cljs
(defonce chsk-router
  (sente/start-chsk-router-loop! event-handler ch-chsk))

;;;; Example: broadcast server>user

;; As an example of push notifications, we'll setup a server loop to broadcast
;; an event to _all_ possible user-ids every 10 seconds:
#+clj
(defonce broadcaster
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

;;;; Setup client buttons

#+cljs
(when-let [target-el (.getElementById js/document "btn1")]
  (.addEventListener target-el "click"
    (fn [ev]
      (logf "Button 1 was clicked (won't receive any reply from server)")
      (chsk-send! [:example/button1 {:had-a-callback? "nope"}]))))

#+cljs
(when-let [target-el (.getElementById js/document "btn2")]
  (.addEventListener target-el "click"
    (fn [ev]
      (logf "Button 2 was clicked (will receive reply from server)")
      (chsk-send! [:example/button2 {:had-a-callback? "indeed"}] 5000
        (fn [cb-reply] (logf "Callback reply: %s" cb-reply))))))

#+cljs
(when-let [target-el (.getElementById js/document "btn-login")]
  (.addEventListener target-el "click"
    (fn [ev]
      (let [user-id (.-value (.getElementById js/document "input-login"))]
        (if (str/blank? user-id)
          (js/alert "Please enter a user-id first")
          (do
            (logf "Logging in with user-id %s" user-id)

            ;;; Use any login procedure you'd like. Here we'll trigger an Ajax
            ;;; POST request that resets our server-side session. Then we ask
            ;;; our channel socket to reconnect, thereby picking up the new
            ;;; session.

            (encore/ajax-lite "/login" {:method :post
                                        :params
                                        {:user-id    (str user-id)
                                         :csrf-token (:csrf-token @chsk-state)}}
              (fn [ajax-resp]
                (logf "Ajax login response: %s" ajax-resp)))

            (sente/chsk-reconnect! chsk)))))))
