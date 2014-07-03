(ns example.mouse.core
  (:require [ring.util.response :refer [file-response]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.session :as ringsession]
            [ring.middleware.anti-forgery :as ring-anti-forgery]
            [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [org.httpkit.server :as http-kit-server]
            [clojure.core.match :as match :refer (match)]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
            [taoensso.timbre    :as timbre]
            [taoensso.sente     :as sente]
            ))

(defonce server (atom nil))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket!
        {:send-buf-ms-ws 10 })] ; awkward mouse movement will be caused here (on sending process from server)
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defn- logf [fmt & xs] (println (apply format fmt xs)))

(defn broadcast-mouse [{:keys [uid x y]} type]
  (doseq [z (:any @connected-uids)]
    (if-not (= z uid) (do
      (match [type]
        ["move"]
          (chsk-send! z [:om-mouse/broadcast {:from uid :x x :y y}])
        ["over"]
          (chsk-send! z [:om-mouse/show {:from uid}])
        ["out"]
          (chsk-send! z [:om-mouse/clear {:from uid}])
        )))))

(defn- event-msg-handler
  [{:as ev-msg :keys [ring-req event ?reply-fn]} _]
  (let [session (:session ring-req)
        uid     (:uid session)
        [id data :as ev] event]
    (match [id data]
      [:om-mouse/position data](do
        (logf "event(:om-mouse/position): %s" data)
        (broadcast-mouse data "move"))
      [:om-mouse/clear data](do
        (logf "event(:om-mouse/clear): %s" data)
        (broadcast-mouse data "out"))
      [:om-mouse/show data](do
        (logf "event(:om-mouse/show): %s" data)
        (broadcast-mouse data "over"))
      :else
        (do (logf "Unmatched event: %s" ev)
          (when-not (:dummy-reply-fn? (meta ?reply-fn))
            (?reply-fn {:umatched-event-as-echoed-from-from-server ev}))))))

; getting from example
(defonce chsk-router
  (sente/start-chsk-router-loop! event-msg-handler ch-chsk))

(defonce broadcaster
  (go-loop [i 0]
    (<! (async/timeout 10000))
    (if (nil? @server)
    (do
      (logf "server isn't instantiated."))
    (do
      (logf (format "Broadcasting server>client: %s" @connected-uids))
      (doseq [uid (:any @connected-uids)]
        (chsk-send! uid
          [:some/broadcast
           {:what-is-this "A broadcast pushed from server"
            :how-often    "Every 10 seconds"
            :to-whom uid
            :i i}])))
    )
    (recur (inc i)))
  )

(defn index [req]
  (let [uid (or (-> req :session :uid) (rand-int 999))
        res (file-response "public/html/index.html" {:root "resources"})]
    (logf "session: %s" (:session req))
    (assoc res :session {:uid uid}))
  )

(defroutes routes
  (GET "/" req (index req))
  (route/files "/" {:root "resources/public"}) ; for css/js
  ;;
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  )

(def app
  (-> routes
;    　(ring-anti-forgery/wrap-anti-forgery
;        {:read-token (fn [req] (-> req :params :csrf-token))})
      ringsession/wrap-session))

; For run server with "lein run"
(defn -main []
  (reset! server (http-kit-server/run-server (var app) {:port 8080}))
  (logf
    (str "Http-kit server is running on `http://localhost:%s/` "
         "(it should be browser-accessible now).")
    (:local-port (meta @server)) @server)
)

(defn stop-server []
  (when-not (nil? @server)
    ;; graceful shutdown: wait 100ms for existing requests to be finished
    ;; :timeout is optional, when no timeout, stop immediately
    (@server :timeout 100)
    (reset! server nil)))

; For starting http-server, Eval on yor LightTable
;(-main)
; For stopping http-server, Eval on yor LightTable
;(stop-server)

