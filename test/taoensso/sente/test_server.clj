(ns taoensso.sente.test-server
  "A test server for relaying messages with sente"
  (:require [compojure.core :refer [defroutes GET POST]]
            [taoensso.sente :as sente]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.reload :as reload]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
            [compojure.route :as route]))

(def ^:private
devcards-html
  "The HTML boilerplate devcards needs"
  (str "<!DOCTYPE html>"
       "<html>"
       "<head>"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">"
       "<meta charset=\"UTF-8\">"
       "</head>"
       "<body>"
       "<script src=\"/js/compiled/sente_devcards.js\" type=\"text/javascript\"></script>"
       "</body>"
       "</html>"))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
      (sente/make-channel-socket! sente-web-server-adapter {})]
  (defroutes
    relay-app-routes
    (GET "/" _ devcards-html)
    (GET "/status" req {:headers {"Content-Type" "text/plain"}
                        :body    (with-out-str (clojure.pprint/pprint req))})
    (GET "/chsk" req (ajax-get-or-ws-handshake-fn req))
    (POST "/chsk" req (ajax-post-fn req))
    (POST "/login" {:keys [session params]} {:status 200 :session (assoc session :uid (:user-id params))})
    (route/not-found "Page not found"))
  (sente/start-chsk-router-loop!
    (fn [{:keys [event ?reply-fn uid]} _]
      (let [[ev-id ?ev-data] event]
        (case ev-id
          :client/broadcast (do (when (ifn? ?reply-fn)
                                  (?reply-fn [:server/ack ?ev-data]))
                                (doseq [uid (:any @connected-uids)]
                                  (send-fn uid [:server/broadcast ?ev-data])))
          :client/message (if-let [{:keys [recipient message]} ?ev-data]
                            (if (contains? (:any @connected-uids) recipient)
                              (do (when (ifn? ?reply-fn)
                                    (?reply-fn [:server/ack ?ev-data]))
                                  (send-fn recipient [:server/relay {:sender  uid
                                                                     :message message}]))
                              (when (ifn? ?reply-fn)
                                (?reply-fn [:server/fail {:message "Specified :recipient is not reporting as connected"
                                                          :data    ?ev-data}])))
                            (when (ifn? ?reply-fn)
                              (?reply-fn [:server/fail {:message "Payload must have :recipient and :message keys"
                                                        :data    ?ev-data}])))
          (when (ifn? ?reply-fn)
            (?reply-fn [:server/error (str "Could not handle ev-id " ev-id)])))))
    ch-recv))

(def relay-app
  (-> relay-app-routes
      (reload/wrap-reload)
      (defaults/wrap-defaults defaults/site-defaults)))