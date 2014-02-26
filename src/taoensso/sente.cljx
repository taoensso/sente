(ns taoensso.sente
  "Channel sockets. Otherwise known as The Shiz.

      Protocol  | client>server | client>server + ack/reply | server>clientS[1] push
    * WebSockets:       ✓              [2]                          ✓  ; [3]
    * Ajax:            [4]              ✓                          [5] ; [3]

    [1] All of a user's (uid's) connected clients (browser tabs, devices, etc.).
    [2] Emulate with cb-uuid wrapping.
    [3] By uid only (=> logged-in users only).
    [4] Emulate with dummy-cb wrapping.
    [5] Emulate with long-polling against uid (=> logged-in users only).

  Special messages (implementation detail):
    * cb replies: :chsk/closed, :chsk/timeout, :chsk/error.
    * client-side events:
        [:chsk/handshake <#{:ws :ajax}>],
        [:chsk/ping      <#{:ws :ajax}>], ; Though no :ajax ping
        [:chsk/state [<#{:open :first-open :closed}> <#{:ws :ajax}]],
        [:chsk/recv  <`server>client`-event>]. ; Async event

    * server-side events:
       [:chsk/bad-edn <edn>],
       [:chsk/bad-event <chsk-event>],
       [:chsk/uidport-open  <#{:ws :ajax}>],
       [:chsk/uidport-close <#{:ws :ajax}>].

    * event wrappers: {:chsk/clj <clj> :chsk/dummy-cb? true} (for [2]),
                      {:chsk/clj <clj> :chsk/cb-uuid <uuid>} (for [4]).

  Implementation notes:
    * A server>client w/cb mechanism would be possible BUT:
      * No fundamental use cases. We can always simulate as server>client w/o cb,
        client>server w or w/o cb.
      * Would yield a significantly more complex code base.
      * Cb semantic is fundamentally incongruous with server>client since
        multiple clients may be connected simultaneously for a single uid.

  General-use notes:
    * Single HTTP req+session persists over entire chsk session but cannot
      modify sessions! Use standard a/sync HTTP Ring req/resp for logins, etc.
    * Easy to wrap standard HTTP Ring resps for transport over chsks. Prefer
      this approach to modifying handlers (better portability).

  Multiple clients (browser tabs, devices, etc.):
    * client>server + ack/reply: sends always to _single_ client. Note that an
      optional _multi_ client reply API wouldn't make sense (we're using a cb).
    * server>clientS push: sends always to _all_ clients.
    * Applications will need to be careful about which method is preferable, and
      when."
  {:author "Peter Taoussanis"}

  #+clj
  (:require [clojure.string :as str]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan
                                                     go go-loop)]
            [clojure.tools.reader.edn :as edn]
            [org.httpkit.server       :as http-kit]
            [taoensso.encore          :as encore]
            [taoensso.timbre          :as timbre])

  #+cljs
  (:require [clojure.string :as str]
            [cljs.core.async :as async :refer (<! >! put! chan)]
            [cljs.reader     :as edn]
            [taoensso.encore :as encore])

  #+cljs
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;;;; TODO
;; * No need/desire for a send buffer, right? Would seem to violate user
;;   expectations (either works now or doesn't, not maybe works later).
;; * Consider later using clojure.browser.net (Ref. http://goo.gl/sdS5wX)
;;   and/or Google Closure for some or all basic WebSockets support,
;;   reconnects, etc.

;;;; Shared (client+server)

(defn- chan? [x]
  #+clj  (instance? clojure.core.async.impl.channels.ManyToManyChannel x)
  #+cljs (instance? cljs.core.async.impl.channels.ManyToManyChannel    x))

(defn event? "Valid [ev-id ?ev-data] form?" [x]
  (and (vector? x) (#{1 2} (count x))
       (let [[ev-id _] x]
         (and (keyword? ev-id) (namespace ev-id)))))

(defn cb-success? [cb-reply] ;; Cb reply need _not_ be `event` form!
  (not (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply)))

#+clj ; For #+cljs we'd rather just throw client-side on bad edn from server
(defn- try-read-edn [edn]
  (try (edn/read-string edn)
       (catch Throwable t [:chsk/bad-edn edn])))

(defn- unwrap-edn-msg-with-?cb->clj "edn -> [clj ?cb-uuid]"
  [edn]
  (let [msg      #+clj  (try-read-edn    edn)
                 #+cljs (edn/read-string edn)
        ?cb-uuid (and (map? msg) (:chsk/cb-uuid msg))
        clj      (if-not ?cb-uuid msg (:chsk/clj msg))]
    [clj ?cb-uuid]))

;;;; Server

#+clj
(defn event-msg?
  "Valid {:client-uuid _ :ring-req _ :event _ :?reply-fn _} form?"
  [x]
  (and (map? x) (= (count x) 4)
       (every? #{:client-uuid :ring-req :event :?reply-fn} (keys x))
       (let [{:keys [client-uuid hk-ch ring-req event ?reply-fn]} x]
         (and (string? client-uuid) ; Set by client (Ajax) or server (WebSockets)
              (map? ring-req)
              (event? event)
              (or (nil? ?reply-fn) (ifn? ?reply-fn))))))

#+clj
(defn- receive-event-msg!
  [ch-recv {:as ev-msg :keys [client-uuid ring-req event ?reply-fn]}]
  (let [ev-msg*
        {:client-uuid client-uuid ; Browser-tab / device identifier
         :ring-req    (select-keys ring-req [:t :locale :session #_:flash :params])
         :event       (if (event? event) event [:chsk/bad-event event])
         :?reply-fn
         (if (ifn? ?reply-fn) ?reply-fn
           (-> (fn [resp-clj] ; Dummy warn fn
                 (timbre/warnf "Trying to reply to non-cb req: %s" event))
               ;; Useful to distinguish between a real cb reply fn and dummy:
               (with-meta {:dummy-reply-fn? true})))}]

    (if (event-msg? ev-msg*) ; Be conservative about what we put to chan!
      (put! ch-recv ev-msg*)
      (timbre/warnf "Bad ev-msg!: %s (%s)" ev-msg* ev-msg))))

#+clj
(defn- ch-pull-ajax-hk-chs!
  "Starts a go loop to pull relevant client hk-chs. Several attempts are made in
  order to provide some reliability against possibly-reconnecting Ajax pollers.
  Pulls at most one hk-ch per client-uuid so works fine with multiple clients.

  Returns a channel to which we'll send the hk-chs set, or close.

  More elaborate implementations (involving tombstones) could cut down on
  unnecessary waiting - but this solution is small, simple, and plenty fast in
  practice."
  [clients_ uid] ; `hk-chs` = http-kit channels
  (let [ch (chan)]
    (go-loop [pulled {} ; {<client-uuid> <hk-ch>}
              n      0]
      (if (= n 3) ; Try three times, always

        ;; >! set of unique-client hk-chs, or nil:
        (if (empty? pulled)
          (async/close! ch)
          (>! ch (set (vals pulled))))

        (let [?pulled-now ; nil or {<client-uuid> <hk-ch>}
              (first
               (swap! clients_
                 (fn [[_ m]]
                   (let [m-in       (get m uid)
                         ks-to-pull (filter #(not (contains? pulled %))
                                            (keys m-in))]
                     (if (empty? ks-to-pull) [nil m]
                       [(select-keys m-in ks-to-pull)
                        (assoc m uid (apply dissoc m-in ks-to-pull))])))))]

          ;; Allow some time for possible poller reconnects:
          (<! (async/timeout (+ 80 (rand-int 50)))) ; ~105ms
          (recur (merge pulled ?pulled-now) (inc n)))))
    ch))

(comment (time (dotimes [_ 50000] (ch-pull-ajax-hk-chs! (atom [nil {}]) 10))))

#+clj
(defn make-channel-socket!
  "Returns `{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn]}`.

  ch-recv - core.async channel ; For server-side chsk request router, will
                               ; receive `event-msg`s from clients.
  send-fn - (fn [user-id ev])   ; For server>clientS push
  ajax-post-fn                - (fn [ring-req]) ; For Ring POST, chsk URL
  ajax-get-or-ws-handshake-fn - (fn [ring-req]) ; For Ring GET, chsk URL (+CSRF)"
  [& [{:keys [recv-buf-or-n]
       :or   {recv-buf-or-n (async/sliding-buffer 1000)}}]]
  (let [ch-recv      (chan recv-buf-or-n)
        clients-ajax (atom [nil {}]) ; [<#{pulled-hk-chs}> {<uid> {<client-uuid> <hk-ch>}}]
        clients-ws   (atom {})       ; {<uid> <#{hk-chs}>}
        ]

    {:ch-recv ch-recv
     :send-fn ; Async server>clientS (by uid) push sender
     (fn [uid ev]
       (timbre/tracef "Chsk send: (->uid %s) %s" uid ev)
       (assert (event? ev))
       (when uid
         (let [send-to-hk-chs! ; Async because of http-kit
               (fn [hk-chs]
                 ;; Remember that http-kit's send to a closed ch is just a no-op
                 (when hk-chs ; No cb, so no need for (cb-fn :chsk/closed)
                   (assert (and (set? hk-chs) (not (empty? hk-chs))))
                   (if (= ev [:chsk/close])
                     (do (timbre/debugf "Chsk CLOSING: %s" uid)
                       (doseq [hk-ch hk-chs] (http-kit/close hk-ch)))

                     (let [ev-edn (pr-str ev)]
                       (doseq [hk-ch hk-chs] ; Broadcast to all uid's clients/devices
                         (http-kit/send! hk-ch ev-edn))))))]

           (send-to-hk-chs! (@clients-ws uid)) ; WebSocket clients
           (go ; Need speed here for broadcasting purposes:
            ;; Prefer broadcasting only to users we know/expect to be online:
            (send-to-hk-chs! (<! (ch-pull-ajax-hk-chs! clients-ajax uid))))

           nil ; Always return nil
           )))

     :ajax-post-fn ; Does not participate in `clients-ajax` (has specific req->resp)
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [msg       (-> ring-req :params :edn try-read-edn)
               dummy-cb? (and (map? msg) (:chsk/dummy-cb? msg))
               clj       (if-not dummy-cb? msg (:chsk/clj msg))]

           (receive-event-msg! ch-recv
             {;; Don't actually use the Ajax POST client-uuid, but we'll set
              ;; one anyway for `event-msg?`:
              :client-uuid (encore/uuid-str)
              :ring-req ring-req
              :event clj
              :?reply-fn
              (when-not dummy-cb?
                (fn reply-fn [resp-clj] ; Any clj form
                  (timbre/tracef "Chsk send (ajax reply): %s" resp-clj)
                  (let [resp-edn (pr-str resp-clj)]
                    ;; true iff apparent success:
                    (http-kit/send! hk-ch resp-edn))))})

           (when dummy-cb?
             (timbre/tracef "Chsk send (ajax reply): dummy-200")
             (http-kit/send! hk-ch (pr-str :chsk/dummy-200))))))

     :ajax-get-or-ws-handshake-fn ; ajax-poll or ws-handshake
     (fn [{:as ring-req {:keys [uid] :as session} :session}]
       (http-kit/with-channel ring-req hk-ch
         (let [client-uuid ; Browser-tab / device identifier
               (str uid "-" ; Security measure (can't be controlled by client)
                 (or (:ajax-client-uuid ring-req)
                     (encore/uuid-str)))

               receive-event-msg!* ; Partial
               (fn [event & [?reply-fn]]
                 (receive-event-msg! ch-recv
                   {:client-uuid  client-uuid ; Fixed (constant) with handshake
                    :ring-req     ring-req    ; ''
                    :event        event
                    :?reply-fn    ?reply-fn}))]

           (if-not (:websocket? ring-req)
             (when uid ; Server shouldn't attempt a non-uid long-pollling GET anyway
               (swap! clients-ajax (fn [[_ m]]
                                     [nil (assoc-in m [uid client-uuid] hk-ch)]))

               ;; Currently relying on `on-close` to _always_ trigger for every
               ;; connection. If that's not the case, will need some kind of gc.
               (http-kit/on-close hk-ch
                 (fn [status]
                   (swap! clients-ajax
                     (fn [[_ m]]
                       (let [new (dissoc (get m uid) client-uuid)]
                         [nil (if (empty? new)
                                (dissoc m uid)
                                (assoc  m uid new))])))
                   (receive-event-msg!* [:chsk/uidport-close :ajax])))
               (receive-event-msg!* [:chsk/uidport-open :ajax]))

             (do
               (timbre/tracef "New WebSocket channel: %s %s"
                 (or uid "(no uid)") (str hk-ch)) ; _Must_ call `str` on ch
               (when uid
                 (swap! clients-ws (fn [m] (assoc m uid (conj (m uid #{}) hk-ch))))
                 (receive-event-msg!* [:chsk/uidport-open :ws]))

               (http-kit/on-receive hk-ch
                 (fn [req-edn]
                   (let [[clj ?cb-uuid] (unwrap-edn-msg-with-?cb->clj req-edn)]
                     (receive-event-msg!* clj
                       (when ?cb-uuid
                         (fn reply-fn [resp-clj] ; Any clj form
                           (timbre/tracef "Chsk send (ws reply): %s" resp-clj)
                           (let [resp-edn (pr-str {:chsk/clj     resp-clj
                                                   :chsk/cb-uuid ?cb-uuid})]
                             ;; true iff apparent success:
                             (http-kit/send! hk-ch resp-edn))))))))

               ;; Currently relying on `on-close` to _always_ trigger for every
               ;; connection. If that's not the case, will need some kind of gc.
               (http-kit/on-close hk-ch
                 (fn [status]
                   (when uid
                     (swap! clients-ws
                       (fn [m]
                         (let [new (disj (m uid #{}) hk-ch)]
                           (if (empty? new) (dissoc m uid)
                                            (assoc  m uid new)))))
                     (receive-event-msg!* [:chsk/uidport-close :ws]))))

               (http-kit/send! hk-ch (pr-str [:chsk/handshake :ws])))))))}))

;;;; Client

#+cljs
(defn assert-event [x]
  (assert (event? x)
          (encore/format "Event should be of [ev-id ?ev-data] form: %s" x)))

#+cljs
(defn- assert-send-args [x ?timeout-ms ?cb]
  (assert-event x)
  (assert (or (and (nil? ?timeout-ms) (nil? ?cb))
              (and (encore/nneg-int? ?timeout-ms)))
          (encore/format
           "cb requires a timeout; timeout-ms should be a +ive integer: %s"
           ?timeout-ms))
  (assert (or (nil? ?cb) (ifn? ?cb) (chan? ?cb))
          (encore/format "cb should be nil, an ifn, or a channel: %s"
                         (type ?cb))))

#+cljs
(defn- pull-unused-cb-fn! [cbs-waiting cb-uuid]
  (when cb-uuid
    (first (swap! cbs-waiting
             (fn [[_ m]] (if-let [f (m cb-uuid)]
                          [f (dissoc m cb-uuid)]
                          [nil m]))))))

#+cljs
(defn- wrap-clj->edn-msg-with-?cb "clj -> [edn ?cb-uuid]"
  [cbs-waiting clj ?timeout-ms ?cb-fn]
  (let [?cb-uuid (when ?cb-fn (encore/uuid-str))
        msg      (if-not ?cb-uuid clj {:chsk/clj clj :chsk/cb-uuid ?cb-uuid})
        ;; Note that if pr-str throws, it'll throw before swap!ing cbs-waiting:
        edn     (pr-str msg)]
    (when ?cb-uuid
      (swap! cbs-waiting
             (fn [[_ m]] [nil (assoc m ?cb-uuid ?cb-fn)]))
      (when ?timeout-ms
        (go (<! (async/timeout ?timeout-ms))
            (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting ?cb-uuid)]
              (cb-fn* :chsk/timeout)))))
    [edn ?cb-uuid]))

#+cljs
(defprotocol IChSocket
  (chsk-type  [chsk] "Returns e/o #{:ws :ajax}.")
  (chsk-open? [chsk] "Returns true iff given channel socket connection seems open.")
  (chsk-send! [chsk ev] [chsk ev ?timeout-ms ?cb]
    "Sends `[ev-id ?ev-data :as event]` over channel socket connection and returns
     true iff send seems successful.")
  (chsk-make! [chsk opts] "Creates and returns a new channel socket connection,
                           or nil on failure."))

#+cljs
(defn- reset-chsk-state! [{:keys [chs open?] :as chsk} now-open?]
  (when (not= @open? now-open?)
    (reset! open? now-open?)
    (let [new-state (if now-open? :open :closed)]
      ;; (encore/debugf "Chsk state change: %s" new-state)
      (put! (:state chs) new-state)
      new-state)))

#+cljs ;; Experimental, undocumented:
(defn- wrap-cb-chan-as-fn [?cb ev]
  (if (or (nil? ?cb) (ifn? ?cb)) ?cb
    (do (assert (chan? ?cb))
        (assert-event ev)
        (let [[ev-id _] ev
              cb-ch ?cb]
          (fn [reply]
            (put! cb-ch [(keyword (str (encore/fq-name ev-id) ".cb"))
                         reply]))))))

#+cljs ;; Handles reconnects, keep-alives, callbacks:
(defrecord ChWebSocket [url chs open? socket-atom kalive-timer kalive-due?
                        cbs-waiting ; [dissoc'd-fn {<uuid> <fn> ...}]
                        ]
  IChSocket
  (chsk-type  [_] :ws)
  (chsk-open? [_] @open?)
  (chsk-send! [chsk ev] (chsk-send! chsk ev nil nil))
  (chsk-send! [chsk ev ?timeout-ms ?cb]
    ;; (encore/debugf "Chsk send: (%s) %s" (if ?cb "cb" "no cb") ev)
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (wrap-cb-chan-as-fn ?cb ev)]
      (if-not @open? ; Definitely closed
        (do (encore/warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))
        (let [[edn ?cb-uuid] (wrap-clj->edn-msg-with-?cb
                              cbs-waiting ev ?timeout-ms ?cb-fn)]
          (try
            (.send @socket-atom edn)
            (reset! kalive-due? false)
            :apparent-success
            (catch js/Error e
              (encore/errorf "Chsk send %s" e)
              (when ?cb-uuid
                (let [cb-fn* (or (pull-unused-cb-fn! cbs-waiting ?cb-uuid)
                                 ?cb-fn)]
                  (cb-fn* :chsk/error)))
              false))))))

  (chsk-make! [chsk {:keys [kalive-ms]}]
    (when-let [WebSocket (or (.-WebSocket    js/window)
                             (.-MozWebSocket js/window))]
      ((fn connect! [attempt]
         (if-let [socket (try (WebSocket. url)
                              (catch js/Error e
                                (encore/errorf "WebSocket js/Error: %s" e)
                                false))]
           (->>
            (doto socket
              (aset "onerror" (fn [ws-ev]
                                (encore/errorf "WebSocket error: %s" ws-ev)))
              (aset "onmessage"
                (fn [ws-ev]
                  (let [edn (.-data ws-ev)
                        ;; Nb may or may NOT satisfy `event?` since we also
                        ;; receive cb replies here!:
                        [clj ?cb-uuid] (unwrap-edn-msg-with-?cb->clj edn)]
                    ;; (assert-event clj) ;; NO!
                    (if (= clj [:chsk/handshake :ws])
                      (reset-chsk-state! chsk true)
                      (if ?cb-uuid
                        (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting ?cb-uuid)]
                          (cb-fn clj)
                          (encore/warnf "Cb reply w/o local cb-fn: %s" clj))
                        (let [_ (assert-event clj)
                              chsk-ev clj]
                          (put! (:recv chs) chsk-ev)))))))
              (aset "onopen"
                (fn [_ws-ev]
                  (reset! kalive-timer
                    (.setInterval js/window
                      (fn []
                        (when @kalive-due? ; Don't ping unnecessarily
                          (chsk-send! chsk [:chsk/ping :ws]))
                        (reset! kalive-due? true))
                      kalive-ms))
                  ;; (reset-chsk-state! chsk true) ; NO, handshake better!
                  ))
              (aset "onclose"
                (fn [_ws-ev]
                  (let [;; onclose will fire repeatedly when server is down
                        state-change? (-> (reset-chsk-state! chsk false)
                                          (boolean))
                        attempt       (if state-change? 0 (inc attempt))]
                    (.clearInterval js/window @kalive-timer)
                    (when (> attempt 0)
                      (encore/warnf "WebSocket closed. Will retry after backoff (attempt %s)."
                            attempt))
                    (encore/set-exp-backoff-timeout! (partial connect! attempt)
                                                     attempt)))))
            (reset! socket-atom))

           (encore/set-exp-backoff-timeout! (partial connect! (inc attempt))
                                            (inc attempt))))
       0)
      chsk)))

#+cljs
(defrecord ChAjaxSocket [url chs open? ajax-client-uuid
                         csrf-token has-uid?]
  IChSocket
  (chsk-type  [_] :ajax)
  (chsk-open? [chsk] @open?)
  (chsk-send! [chsk ev] (chsk-send! chsk ev nil nil))
  (chsk-send! [chsk ev ?timeout-ms ?cb]
    ;; (encore/debugf "Chsk send: (%s) %s" (if ?cb "cb" "no cb") ev)
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (wrap-cb-chan-as-fn ?cb ev)]
      (if-not (or @open? (= ev [:chsk/handshake :ajax]))
        ;; Definitely closed
        (do (encore/warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))
        (do
          (encore/ajax-lite url
           {:method :post :timeout ?timeout-ms
            :params
            (let [dummy-cb? (not ?cb-fn)
                  msg       (if-not dummy-cb? ev {:chsk/clj       ev
                                                  :chsk/dummy-cb? true})
                  edn       (pr-str msg)]
              {:_ (encore/now-udt) ; Force uncached resp
               :edn edn :csrf-token csrf-token})}

           (fn ajax-cb [{:keys [content error]}]
             (if error
               (if (= error :timeout)
                 (when ?cb-fn (?cb-fn :chsk/timeout))
                 (do (reset-chsk-state! chsk false)
                     (when ?cb-fn (?cb-fn :chsk/error))))

               (let [resp-edn content
                     resp-clj (edn/read-string resp-edn)]
                 (if ?cb-fn (?cb-fn resp-clj)
                   (when (not= resp-clj :chsk/dummy-200)
                     (encore/warnf "Cb reply w/o local cb-fn: %s" resp-clj)))
                 (reset-chsk-state! chsk true)))))

          :apparent-success))))

  (chsk-make! [chsk {:keys [timeout]}]
    ;; As currently implemented (i.e. without server-side broadcast features),
    ;; there's no point in creating an Ajax poller if we're not logged in since
    ;; there'd be no way for the server to identify us when sending non-request
    ;; messages.
    (if-not has-uid?
      (reset-chsk-state! chsk true) ; Must still mark as open to enable sends
      ((fn async-poll-for-update! [& [new-conn?]]
         (let [ajax-req! ; Just for Pace wrapping below
               (fn []
                 (encore/ajax-lite url
                  {:method :get :timeout timeout
                   :params {:_ (encore/now-udt) ; Force uncached resp
                            :ajax-client-uuid ajax-client-uuid}}
                  (fn ajax-cb [{:keys [content error]}]
                    (if error
                      (if (= error :timeout)
                        (async-poll-for-update!)
                        (do (reset-chsk-state! chsk false)
                            ;; TODO Need a backoff mechanism!
                            (async-poll-for-update! :new-conn)))

                      (let [edn content
                            ev  (edn/read-string edn)]
                        ;; The Ajax long-poller is used only for events, never cbs:
                        (assert-event ev)
                        (put! (:recv chs) ev)
                        (reset-chsk-state! chsk true)
                        (async-poll-for-update!))))))]

           (if-let [pace (.-Pace js/window)]
             (.ignore pace ajax-req!) ; Pace.js shouldn't trigger for long-polling
             (ajax-req!)))

         ;; Try handshake to confirm working conn (will enable sends)
         (when new-conn? (chsk-send! chsk [:chsk/handshake :ajax])))
       :new-conn))
    chsk))

#+cljs
(defn- chsk-url [path & [websocket?]]
  (let [{:keys [protocol host pathname]} (encore/get-window-location)]
    (str (if-not websocket? protocol (if (= protocol "https:") "wss:" "ws:"))
         "//" host (or path pathname))))

#+cljs
(defn make-channel-socket!
  "Returns `{:keys [chsk ch-recv send-fn]}` for a new ChWebSocket or ChAjaxSocket that
  provides an ISocket interface:
  * An efficient, convenient, high-performance client/server message API.
  * Both callback and channel (routing) style bidirectional support.
  * Encapsulation of all low-level nastiness like capability fallback,
    reconnects, keep-alives, error logging, etc.

  Note that the *same* URL is used for: WebSockets, POSTs, GETs. Server-side
  routes should be configured accordingly."
  [url {:keys [csrf-token has-uid?]}
   & [{:keys [type recv-buf-or-n ws-kalive-ms lp-timeout]
       :or   {type          :auto
              recv-buf-or-n (async/sliding-buffer 10)
              ws-kalive-ms  38000
              lp-timeout    38000}}]]

  (when (str/blank? csrf-token)
    (encore/log "WARNING: No csrf-token provided"))

  (let [;; Want _separate_ buffers for state+recv even if we're later merging
        chs {:state    (chan (async/sliding-buffer 1))
             :recv     (chan recv-buf-or-n)
             :internal (chan recv-buf-or-n)}

        chsk
        (or
         (and (not= type :ajax)
              (chsk-make!
               (ChWebSocket. (chsk-url url :ws)
                chs (atom false) (atom nil) (atom nil) (atom true)
                (atom [nil {}]))
               {:kalive-ms ws-kalive-ms}))

         (and (not= type :ws)
              (let [;; Unchanging over multiple long-poll (re)connects:
                    ajax-client-uuid (encore/uuid-str)]
                (chsk-make!
                 (ChAjaxSocket. (chsk-url url) chs (atom false)
                   ajax-client-uuid csrf-token has-uid?)
                 {:timeout lp-timeout}))))

        type* (chsk-type chsk) ; Actual reified type
        ever-opened? (atom false)
        state*       (fn [clj] (if (or (not= clj :open) @ever-opened?) clj
                                  (do (reset! ever-opened? true) :first-open)))]

    (when chsk
      {:chsk chsk
       :ch-recv
       (async/merge
        [(->> (:internal chs) (async/map< (fn [ev] {:pre [(event? ev)]} ev)))
         (->> (:state chs) (async/map< (fn [clj] [:chsk/state [(state* clj) type*]])))
         (->> (:recv  chs) (async/map< (fn [clj] [:chsk/recv  clj])))])
       :send-fn (partial chsk-send! chsk)})))

;;;; Routers

#+clj
(defn start-chsk-router-loop! [event-msg-handler ch]
  (go-loop []
    (try
      (let [event-msg (<! ch)]
        (try
          (timbre/tracef "Event-msg: %s" event-msg)
          (event-msg-handler event-msg ch)
          (catch Throwable t
            (timbre/errorf t "Chsk-router-loop handling error: %s" event-msg))))
      (catch Throwable t
        (timbre/errorf t "Chsk-router-loop channel error!")))
    (recur)))

#+cljs
(defn start-chsk-router-loop! [event-handler ch]
  (go-loop []
    (let [[id data :as event] (<! ch)]
      ;; Provide ch to handler to allow event injection back into loop:
      (event-handler event ch) ; Allow errors to throw
      (recur))))
