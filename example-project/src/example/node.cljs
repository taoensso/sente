(ns example.node
  (:require [taoensso.sente :as sente]
            [cljs.core.async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)

(defn event-msg-handler [event]
  (println "event: " event))

(defn start []
  (println "Hello nodejs!")
  (let [;; Serializtion format, must use same val for client + server:
        packer :edn ; Default packer, a good choice in most cases
        {:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket-client!
         "/chsk" ; Must match server Ring routing URL
         {:type   :auto
          :packer packer
          :host "localhost:8080"})]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom

    (sente/start-client-chsk-router!
     ch-chsk event-msg-handler)

    (js/setInterval #(do (println "sending")
                        (chsk-send! [:example/node {:had-a-callback? "yep"}] 5000 (fn [reply] (println "Reply: " reply)))) 5000)
    )
  )

(set! *main-cli-fn* start)
