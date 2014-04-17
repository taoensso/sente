(ns taoensso.sente
  "Channel sockets. Otherwise known as The Shiz.

      Protocol  | client>server | client>server + ack/reply | server>user[1] push
    * WebSockets:       ✓              [2]                          ✓  ; [3]
    * Ajax:            [4]              ✓                          [5] ; [3]

    [1] ALL of a user's connected clients (browser tabs, devices, etc.).
        Note that user > session > client > connection for consistency over time
        + multiple devices.
    [2] Emulate with cb-uuid wrapping.
    [3] By uid only (=> logged-in users only).
    [4] Emulate with dummy-cb wrapping.
    [5] Emulate with long-polling against uid (=> logged-in users only).

  Abbreviations:
    * chsk  - channel socket.
    * hk-ch - Http-kit Channel.
    * uid   - User id. An application-specified identifier unique to each user
              and sessionized under `:uid` key to enable server>user push.
              May have semantic meaning (e.g. username, email address), or not
              (e.g. random uuid) - app's discresion.
    * cb    - callback.
    * tout  - timeout.
    * ws    - WebSocket/s.

  Special messages (implementation detail):
    * cb replies: :chsk/closed, :chsk/timeout, :chsk/error.
    * client-side events:
        [:chsk/handshake <#{:ws :ajax}>],
        [:chsk/ping      <#{:ws :ajax}>], ; Though no :ajax ping
        [:chsk/state [<#{:open :first-open :closed}> <#{:ws :ajax}]],
        [:chsk/recv  <[buffered-evs]>]. ; server>user push

    * server-side events:
       [:chsk/bad-edn <edn>],
       [:chsk/bad-event <chsk-event>],
       [:chsk/uidport-open  <#{:ws :ajax}>],
       [:chsk/uidport-close <#{:ws :ajax}>].

    * event wrappers: {:chsk/clj <clj> :chsk/dummy-cb? true} (for [2]),
                      {:chsk/clj <clj> :chsk/cb-uuid <uuid>} (for [4]).

  Notable implementation details:
    * Edn is used as a flexible+convenient transfer format, but can be seen as
      an implementation detail. Users may apply additional string encoding (e.g.
      JSON) at will. (This would incur a cost, but it'd be negligable compared
      to even the fastest network transfer times).
    * No server>client (with/without cb) mechanism is provided since:
      - server>user is what people actually want 90% of the time, and is a
        preferable design pattern in general IMO.
      - server>client could be (somewhat inefficiently) simulated with server>user.
    * core.async is used liberally where brute-force core.async allows for
      significant implementation simplifications. We lean on core.async's strong
      efficiency here.

  General-use notes:
    * Single HTTP req+session persists over entire chsk session but cannot
      modify sessions! Use standard a/sync HTTP Ring req/resp for logins, etc.
    * Easy to wrap standard HTTP Ring resps for transport over chsks. Prefer
      this approach to modifying handlers (better portability)."
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
  (:require [clojure.string  :as str]
            [cljs.core.async :as async :refer (<! >! put! chan)]
            [cljs.reader     :as edn]
            [taoensso.encore :as encore :refer (format)])

  #+cljs
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;;;; TODO
;; * Allow client-side `:has-uid?` opt to be toggled after chsk creation.
;; * Protocol-ize http server stuff.
;; * Performance optimization: client>server event buffering.
;; * Use new `connected-uids_` data for `send-buffered-evs-ajax!`.
;; * Use new `connected-uids_` data for better `[:chsk/uidport-close :ajax]`
;;   event generation.

;;;; Shared (client+server)

(defn- chan? [x]
  #+clj  (instance? clojure.core.async.impl.channels.ManyToManyChannel x)
  #+cljs (instance? cljs.core.async.impl.channels.ManyToManyChannel    x))

(defn- validate-event-form [x]
  (cond  (not (vector? x))        :wrong-type
         (not (#{1 2} (count x))) :wrong-length
   :else (let [[ev-id _] x]
           (cond (not (keyword? ev-id))  :wrong-id-type
                 (not (namespace ev-id)) :unnamespaced-id
                 :else nil))))

(defn event? "Valid [ev-id ?ev-data] form?" [x] (nil? (validate-event-form x)))

(defn assert-event [x]
  (when-let [?err (validate-event-form x)]
    (let [err-fmt
          (str
           (case ?err
             :wrong-type   "Malformed event (wrong type)."
             :wrong-length "Malformed event (wrong length)."
             (:wrong-id-type :unnamespaced-id)
             "Malformed event (`ev-id` should be a namespaced keyword)."
             :else "Malformed event (unknown error).")
           " Event should be of `[ev-id ?ev-data]` form: %s")]
      (throw (ex-info (format err-fmt (str x)) {:malformed-event x})))))

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
         :ring-req    ring-req
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
(defn- send-buffered-evs-ws!
  "Actually pushes buffered events (edn) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-edn]
  (doseq [hk-ch (@conns_ uid)]
    (http-kit/send! hk-ch buffered-evs-edn)))

#+clj
(defn- send-buffered-evs-ajax!
  "Actually pushes buffered events (edn) to all uid's Ajax conns.
  Atomically pulls Ajax long-polling conns from connected conns atom. As a
  reliability measure against possibly-REconnecting Ajax pollers (possibly from
  multiple clients) - will attempt several pulls over time (at most one hk-ch
  per client-uuid to prevent sending the same content to a client more than
  once).

  More elaborate implementations (involving tombstones) could cut down on
  unnecessary waiting - but this solution is small, simple, and plenty good in
  practice due to core.async's efficiency."
  [conns_ uid buffered-evs-edn & [{:keys [nattempts ms-base ms-rand]
                                   ;; 5 attempts at ~85ms ea = 425ms
                                   :or   {nattempts 5
                                          ms-base   50
                                          ms-rand   50}}]]
  (go-loop [n 0 client-uuids-satisfied #{}]
    (let [?pulled ; nil or {<client-uuid> <hk-ch>}
          (encore/swap-in! conns_ ; {<uid> {<client-uuid> <hk-ch>}}
            nil
            (fn [m]
              (let [m-in       (get m uid)
                    ks-to-pull (remove client-uuids-satisfied (keys m-in))]
                (if (empty? ks-to-pull)
                  (encore/swapped m nil)
                  (encore/swapped
                   (assoc m uid (apply dissoc m-in ks-to-pull))
                   (select-keys m-in ks-to-pull))))))]
      (assert (or (nil? ?pulled) (map? ?pulled)))
      (let [?newly-satisfied
            (when ?pulled
              (reduce-kv (fn [s client-uuid hk-ch]
                           (if-not (http-kit/send! hk-ch buffered-evs-edn)
                             s ; hk-ch may have closed already!
                             (conj s client-uuid))) #{} ?pulled))]
        (when (< n nattempts) ; Try repeatedly, always
          ;; Allow some time for possible poller reconnects:
          (<! (async/timeout (+ ms-base (rand-int ms-rand))))
          (recur (inc n) (into client-uuids-satisfied ?newly-satisfied)))))))

#+clj
(defn make-channel-socket!
  "Returns `{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
                    connected-uids]}`:
    * ch-recv - core.async channel ; For server-side chsk request router, will
                                   ; receive `event-msg`s from clients.
    * send-fn - (fn [user-id ev])   ; For server>user push
    * ajax-post-fn                - (fn [ring-req]) ; For Ring CSRF-POST, chsk URL
    * ajax-get-or-ws-handshake-fn - (fn [ring-req]) ; For Ring GET,       chsk URL
    * connected-uids ; Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}})

  Options:
    * recv-buf-or-n    ; Used for ch-recv buffer
    * user-id-fn       ; (fn [ring-req]) -> unique user-id, as used by
                       ; server>user push.
    * send-buf-ms-ajax ; [1]
    * send-buf-ms-ws   ; [1]

  [1] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."
  [& [{:keys [recv-buf-or-n send-buf-ms-ajax send-buf-ms-ws user-id-fn]
       :or   {recv-buf-or-n (async/sliding-buffer 1000)
              send-buf-ms-ajax 100
              send-buf-ms-ws   30
              user-id-fn (fn [ring-req] (get-in ring-req [:session :uid]))}}]]
  {:pre [(encore/pos-int? send-buf-ms-ajax)
         (encore/pos-int? send-buf-ms-ws)]}

  (let [ch-recv     (chan recv-buf-or-n)

        ;;; Internal hk-ch storage:
        conns-ws_   (atom {}) ; {<uid> <#{hk-chs}>}
        conns-ajax_ (atom {}) ; {<uid> {<client-uuid> <hk-ch>}}

        ;;; Separate buffers for easy atomic pulls w/ support for diff timeouts:
        send-buffers-ws_   (atom {}) ; {<uid> [<buffered-evs> <#{ev-uuids}>]}
        send-buffers-ajax_ (atom {}) ; ''

        ;;; Connected uids:
        ajax-udts-last-connected_ (atom {}) ; {<uid> <udt-last-connected>}
        connected-uids_           (atom {:ws #{} :ajax #{} :any #{}})]

    {:ch-recv ch-recv
     :connected-uids connected-uids_
     :send-fn ; server>user (by uid) push
     (fn [uid ev & [{:as _opts :keys [flush-send-buffer?]}]]
       (timbre/tracef "Chsk send: (->uid %s) %s" uid ev)
       (assert-event ev)
       (assert (not (nil? uid))
         "server>user push requires a non-nil user-id (client session :uid by default)")

       (let [ev-uuid    (encore/uuid-str)
             buffer-ev! (fn [send-buffers_]
                            (encore/swap-in! send-buffers_ [uid]
                              (fn [old-v]
                                (if-not old-v [[ev] #{ev-uuid}]
                                  (let [[buffered-evs ev-uuids] old-v]
                                    [(conj buffered-evs ev)
                                     (conj ev-uuids     ev-uuid)])))))
             flush-buffer!
             (fn [send-buffers_ flush-f]
               (when-let [pulled (encore/swap-in! send-buffers_ nil
                                   (fn [m] (encore/swapped (dissoc m uid)
                                                          (get m uid))))]
                 (let [[buffered-evs ev-uuids] pulled]
                   (assert (vector? buffered-evs))
                   (assert (set?    ev-uuids))
                   ;; Don't actually flush unless the event buffered with _this_
                   ;; send call is still buffered (awaiting flush). This means
                   ;; that we'll have many (go block) buffer flush calls that'll
                   ;; noop. They're cheap, and this approach is preferable to
                   ;; alternatives like flush workers.
                   (when (contains? ev-uuids ev-uuid)
                     (let [buffered-evs-edn (pr-str buffered-evs)]
                       (flush-f uid buffered-evs-edn))))))]

         (if (= ev [:chsk/close]) ; Experimental (undocumented)
           (do ; Currently non-flushing, closes only WebSockets:
             (timbre/debugf "Chsk CLOSING: %s" uid)
             (doseq [hk-ch (@conns-ws_ uid)] (http-kit/close hk-ch)))

           (do
             ;;; Buffer event:
             (buffer-ev! send-buffers-ws_)
             (buffer-ev! send-buffers-ajax_)

             ;;; Flush event buffers after relevant timeouts:
             ;; * May actually flush earlier due to another timeout.
             ;; * We send to _all_ of a uid's connections.
             ;; * Broadcasting is possible but I'd suggest doing it rarely, and
             ;;   only to users we know/expect are actually online.
             (go (when-not flush-send-buffer? (<! (async/timeout send-buf-ms-ws)))
                 (flush-buffer! send-buffers-ws_
                   (partial send-buffered-evs-ws! conns-ws_)))
             (go (when-not flush-send-buffer? (<! (async/timeout send-buf-ms-ajax)))
                 (flush-buffer! send-buffers-ajax_
                   (partial send-buffered-evs-ajax! conns-ajax_))))))

       nil)

     :ajax-post-fn ; Does not participate in `conns-ajax` (has specific req->resp)
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [msg       (-> ring-req :params :edn try-read-edn)
               dummy-cb? (and (map? msg) (:chsk/dummy-cb? msg))
               clj       (if-not dummy-cb? msg (:chsk/clj msg))]

           (receive-event-msg! ch-recv
             {;; Currently unused for non-lp POSTs, but necessary for `event-msg?`:
              :client-uuid "degenerate-ajax-post-fn-uuid" ; (encore/uuid-str)
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
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [uid (user-id-fn ring-req)
               client-uuid ; Browser-tab / device identifier
               (str uid "-" ; Security measure (can't be controlled by client)
                 (or (get-in ring-req [:params :ajax-client-uuid])
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
               (swap! conns-ajax_ (fn [m] (assoc-in m [uid client-uuid] hk-ch)))

               (swap! ajax-udts-last-connected_ assoc uid (encore/now-udt))
               (swap! connected-uids_
                 (fn [{:keys [ws ajax any]}]
                   {:ws ws :ajax (conj ajax uid) :any (conj any uid)}))

               ;; Currently relying on `on-close` to _always_ trigger for every
               ;; connection. If that's not the case, will need some kind of gc.
               (http-kit/on-close hk-ch
                 (fn [status]
                   (swap! conns-ajax_
                     (fn [m]
                       (let [new (dissoc (get m uid) client-uuid)]
                         (if (empty? new)
                           (dissoc m uid)
                           (assoc  m uid new)))))

                   ;; Maintain `connected-uids_`. We can't take Ajax disconnect
                   ;; as a reliable indication that user has actually left
                   ;; (user's poller may be reconnecting). Instead, we'll wait
                   ;; a little while and mark the user as gone iff no further
                   ;; connections occurred while waiting.
                   ;;
                   (let [udt-disconnected (encore/now-udt)]
                     ;; Allow some time for possible poller reconnects:
                     (go (<! (async/timeout 5000))
                         (let [poller-has-stopped?
                               (encore/swap-in! ajax-udts-last-connected_ nil
                                 (fn [m]
                                   (let [?udt-last-connected (get m uid)
                                         poller-has-stopped?
                                         (and ?udt-last-connected ; Not yet gc'd
                                              (>= udt-disconnected
                                                  ?udt-last-connected))]
                                     (if poller-has-stopped?
                                       (encore/swapped (dissoc m uid) true) ; gc
                                       (encore/swapped m false)))))]
                           (when poller-has-stopped?
                             (swap! connected-uids_
                               (fn [{:keys [ws ajax any]}]
                                 {:ws ws :ajax (disj ajax uid)
                                  :any (if (contains? ws uid) any
                                         (disj any uid))}))))))

                   (receive-event-msg!* [:chsk/uidport-close :ajax])))
               (receive-event-msg!* [:chsk/uidport-open :ajax]))

             (do
               (timbre/tracef "New WebSocket channel: %s %s"
                 (or uid "(no uid)") (str hk-ch)) ; _Must_ call `str` on ch
               (when uid
                 (swap! conns-ws_ (fn [m] (assoc m uid (conj (m uid #{}) hk-ch))))
                 (swap! connected-uids_
                 (fn [{:keys [ws ajax any]}]
                   {:ws (conj ws uid) :ajax ajax :any (conj any uid)}))
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
                     (swap! conns-ws_
                       (fn [m]
                         (let [new (disj (m uid #{}) hk-ch)]
                           (if (empty? new) (dissoc m uid)
                                            (assoc  m uid new)))))

                     (swap! connected-uids_
                       (fn [{:keys [ws ajax any]}]
                         {:ws (disj ws uid) :ajax ajax
                          :any (if (contains? ajax uid) any
                                 (disj any uid))}))

                     (receive-event-msg!* [:chsk/uidport-close :ws]))))
               (http-kit/send! hk-ch (pr-str [:chsk/handshake :ws])))))))}))

;;;; Client

#+cljs
(defn- assert-send-args [x ?timeout-ms ?cb]
  (assert-event x)
  (assert (or (and (nil? ?timeout-ms) (nil? ?cb))
              (and (encore/nneg-int? ?timeout-ms)))
          (format "cb requires a timeout; timeout-ms should be a +ive integer: %s"
           ?timeout-ms))
  (assert (or (nil? ?cb) (ifn? ?cb) (chan? ?cb))
          (format "cb should be nil, an ifn, or a channel: %s" (type ?cb))))

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

#+cljs
(defn- receive-buffered-evs!
  [ch-recv clj] {:pre [(vector? clj)]}
  (let [buffered-evs clj]
    (doseq [ev buffered-evs]
      (assert-event ev)
      (put! ch-recv ev))))

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
    (when-let [WebSocket (or (aget js/window "WebSocket")
                             (aget js/window "MozWebSocket"))]
      ((fn connect! [nattempt]
         (let [retry!
               (fn []
                 (let [nattempt* (inc nattempt)]
                   (.clearInterval js/window @kalive-timer)
                   (encore/warnf "Chsk is closed: will try reconnect (%s)."
                                 nattempt*)
                   (encore/set-exp-backoff-timeout!
                    (partial connect! nattempt*) nattempt*)))]

           (if-let [socket (try (WebSocket. url)
                                (catch js/Error e
                                  (encore/errorf "WebSocket js/Error: %s" e)
                                  false))]
             (->>
              (doto socket
                (aset "onerror"
                  (fn [ws-ev] (encore/errorf "WebSocket error: %s" ws-ev)))
                (aset "onmessage" ; Nb receives both push & cb evs!
                  (fn [ws-ev]
                    (let [edn (aget ws-ev "data")
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
                          (let [buffered-evs clj]
                            (receive-buffered-evs! (:recv chs) buffered-evs)))))))
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
                (aset "onclose" ; Fires repeatedly when server is down
                  (fn [_ws-ev] (retry!))))

            (reset! socket-atom))

             ;; Couldn't even get a socket:
             (retry!))))
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
            :resp-type :text ; Prefer to do our own edn reading
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
      ((fn async-poll-for-update! [nattempt]

         (let [retry!
               (fn []
                 (let [nattempt* (inc nattempt)]
                   (encore/warnf
                    "Chsk is closed: will try reconnect (%s)."
                    nattempt*)
                   (encore/set-exp-backoff-timeout!
                    (partial async-poll-for-update! nattempt*)
                    nattempt*)))

               ajax-req! ; Just for Pace wrapping below
               (fn []
                 (encore/ajax-lite url
                  {:method :get :timeout timeout
                   :resp-type :text ; Prefer to do our own edn reading
                   :params {:_ (encore/now-udt) ; Force uncached resp
                            :ajax-client-uuid ajax-client-uuid}}
                  (fn ajax-cb [{:keys [content error]}]
                    (if error
                      (if (= error :timeout)
                        (async-poll-for-update! 0)
                        (do (reset-chsk-state! chsk false)
                            (retry!)))

                      ;; The Ajax long-poller is used only for events, never cbs:
                      (let [edn          content
                            buffered-evs (edn/read-string edn)]
                        (receive-buffered-evs! (:recv chs) buffered-evs)
                        (reset-chsk-state! chsk true)
                        (async-poll-for-update! 0))))))]

           (if-let [pace (aget js/window "Pace")]
             ;; Assumes relevant extern is defined for :advanced mode compilation:
             (.ignore pace ajax-req!) ; Pace.js shouldn't trigger for long-polling
             (ajax-req!)))

         (when-not @open?
           ;; (encore/debugf "Attempting chsk Ajax handshake")
           ;; Try handshake to confirm working conn (will enable sends):
           (chsk-send! chsk [:chsk/handshake :ajax])))
       0))
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
  [url &
   & [{:keys [csrf-token has-uid?
              type recv-buf-or-n ws-kalive-ms lp-timeout]
       :or   {type          :auto
              recv-buf-or-n (async/sliding-buffer 2048) ; Mostly for buffered-evs
              ws-kalive-ms  38000
              lp-timeout    38000}}
      _deprecated-more-opts]]

  (when (not (nil? _deprecated-more-opts))
    (encore/warnf
     "`make-channel-socket!` fn signature CHANGED with Sente v0.10.0."))

  (when (str/blank? csrf-token)
    (encore/warnf "No csrf-token provided"))

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
       :send-fn (partial chsk-send! chsk)
       :ch-recv
       (async/merge
        [(->> (:internal chs) (async/map< (fn [ev] {:pre [(event? ev)]} ev)))
         (->> (:state chs) (async/map< (fn [clj] [:chsk/state [(state* clj) type*]])))
         (->> (:recv  chs) (async/map< (fn [ev]  [:chsk/recv  ev])))])})))

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
