(ns example.mouse.core
  (:require-macros [cljs.core.match.macros :refer (match)]
                   )
  (:require [cljs.reader :as reader]
            [goog.dom :as gdom]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [cljs.core.match]
            [cljs.core.async :as async  :refer (<! >! put! chan)]
            [taoensso.encore :as encore :refer (logf)]
            [taoensso.sente  :as sente  :refer (cb-success?)]
            )
  (:import [goog.net.EventType]
           [goog.events EventType]
           ))

(enable-console-print!)

(println "Hello world! from om-mouse.core")

(def uid (atom -1))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )

(def pointer (atom (js/document.getElementById "pointer")))
(def surface (atom (js/document.getElementById "surface")))

(defn surface-offset [] (do
  (let [x (.getBoundingClientRect (js/document.getElementById "surface"))]
                         {:top (.-top x) :left (.-left x)})))

(defn pointer-move [data]
  (let [x (+ (:x (get data 1)) (:left (surface-offset)))
        y (+ (:y (get data 1)) (:top (surface-offset)))]
    (set! (.-style.left @pointer) x)
    (set! (.-style.top @pointer) y)))

(defn- event-handler [[id data :as ev] _]
  (logf "Event: %s" ev)
  (match [id data]
    [:chsk/state {:first-open? true}] (do
                                        (reset! uid (:uid data))
                                        (logf "registered-uid: %s" @uid)
                                        (let [msg (gdom/getElement "uid")]
                                          (set! (.-innerHTML msg) [:uid @uid])))
    ;;
    [:chsk/recv  [:om-mouse/broadcast _]] (pointer-move data)
    ;;
    [:chsk/recv  [:om-mouse/clear _]]  (set! (.-style.visibility @pointer) "hidden")
    [:chsk/recv  [:om-mouse/show _]]  (set! (.-style.visibility @pointer) "visible")
    [:chsk/recv  [:some/broadcast _]]  (logf "broadcast signal was received.")
    :else (logf "Unmatched event: %s" ev)))

(defonce chsk-router
  (sente/start-chsk-router-loop! event-handler ch-chsk))

; ---

(defn mouse-move [event]
  (let [x (- (.-clientX event) (:left (surface-offset)))
        y (- (.-clientY event) (:top (surface-offset)))
        msg (gdom/getElement "message")]
      (set! (.-innerHTML msg) [:x x :y y]) ; notmal writing way to innerHTML
      (chsk-send! [:om-mouse/position {:uid @uid :x x :y y}])
    )
  )

(events/listen js/surface event-type/MOUSEMOVE #(mouse-move %))

(defn mouse-over [event]
  (chsk-send! [:om-mouse/show {:uid @uid}])
  )

(events/listen js/surface event-type/MOUSEOVER #(mouse-over %))

(defn mouse-out [event]
  (chsk-send! [:om-mouse/clear {:uid @uid}])
  )

(events/listen js/surface event-type/MOUSEOUT  #(mouse-out %))
