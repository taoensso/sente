(ns taoensso.sente
  "Channel sockets. Otherwise known as The Shiz.

      Protocol  | client>server | client>server + ack/reply | server>user[1] push
    * WebSockets:       ✓              [2]                          ✓
    * Ajax:            [3]              ✓                          [4]

    [1] By user-id => ALL of a user's connected clients (browser tabs, devices,
        etc.). Note that user > session > client > connection for consistency
        over time + multiple devices.
    [2] Emulate with cb-uuid wrapping.
    [3] Emulate with dummy-cb wrapping.
    [4] Emulate with long-polling.

  Abbreviations:
    * chsk  - Channel socket.
    * hk-ch - Http-kit Channel.
    * uid   - User-id. An application-specified identifier unique to each user
              and sessionized under `:uid` key to enable server>user push.
              May have semantic meaning (e.g. username, email address), or not
              (e.g. random uuid) - app's discresion.
    * cb    - Callback.
    * tout  - Timeout.
    * ws    - WebSocket/s.

  Special messages (implementation detail):
    * Callback replies: :chsk/closed, :chsk/timeout, :chsk/error.
    * Client-side events:
        [:chsk/handshake [<?uid> <?csrf-token>]],
        [:chsk/ws-ping],
        [:chsk/state <new-state>],
        [:chsk/recv  <[buffered-evs]>] ; server>user push

    * Server-side events:
       [:chsk/bad-edn <edn>],
       [:chsk/bad-event <chsk-event>],
       [:chsk/uidport-open],
       [:chsk/uidport-close].

    * Event wrappers: {:chsk/clj <clj> :chsk/dummy-cb? true} (for [2]),
                      {:chsk/clj <clj> :chsk/cb-uuid <uuid>} (for [4]).

  Notable implementation details:
    * Edn is used as a flexible+convenient transfer format, but can be seen as
      an implementation detail. Users may apply additional string encoding (e.g.
      JSON) at will. (This would incur a cost, but it'd be negligable compared
      to even the fastest network transfer times).
    * core.async is used liberally where brute-force core.async allows for
      significant implementation simplifications. We lean on core.async's strong
      efficiency here.
    * For WebSocket fallback we use long-polling rather than HTTP 1.1 streaming
      (chunked transfer encoding). Http-kit _does_ support chunked transfer
      encoding but a small minority of browsers &/or proxies do not. Instead of
      implementing all 3 modes (WebSockets, streaming, long-polling) - it seemed
      reasonable to focus on the two extremes (performance + compatibility). In
      any case client support for WebSockets is growing rapidly so fallback
      modes will become increasingly irrelevant while the extra simplicity will
      continue to pay dividends.

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

;;;; Shared (client+server)

(defn- chan? [x]
  #+clj  (instance? clojure.core.async.impl.channels.ManyToManyChannel x)
  #+cljs (instance?    cljs.core.async.impl.channels.ManyToManyChannel x))

(defn- validate-event-form [x]
  (cond
    (not (vector? x))        :wrong-type
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
(defn- send-buffered-evs>ws-clients!
  "Actually pushes buffered events (edn) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-edn]
  (doseq [hk-ch (get-in @conns_ [:ws uid])]
    (http-kit/send! hk-ch buffered-evs-edn)))

#+clj
(defn- send-buffered-evs>ajax-clients!
  "Actually pushes buffered events (edn) to all uid's Ajax conns. Allows some
  time for possible Ajax poller reconnects."
  [conns_ uid buffered-evs-edn & [{:keys [nmax-attempts ms-base ms-rand]
                                   ;; <= 7 attempts at ~135ms ea = 945ms
                                   :or   {nmax-attempts 7
                                          ms-base       90
                                          ms-rand       90}}]]
  (comment (* 7 (+ 90 (/ 90 2.0))))
  (let [;; All connected/possibly-reconnecting client uuids:
        client-uuids-unsatisfied (keys (get-in @conns_ [:ajax uid]))]
    (when-not (empty? client-uuids-unsatisfied)
      ;; (println "client-uuids-unsatisfied: " client-uuids-unsatisfied)
      (go-loop [n 0 client-uuids-satisfied #{}]
        (let [?pulled ; nil or {<client-uuid> [<?hk-ch> <udt-last-connected>]}
              (encore/swap-in! conns_ [:ajax uid]
                (fn [m] ; {<client-uuid> [<?hk-ch> <udt-last-connected>]}
                  (let [ks-to-pull (remove client-uuids-satisfied (keys m))]
                    ;; (println "ks-to-pull: " ks-to-pull)
                    (if (empty? ks-to-pull)
                      (encore/swapped m nil)
                      (encore/swapped
                        (reduce
                          (fn [m k]
                            (let [[?hk-ch udt-last-connected] (get m k)]
                              (assoc m k [nil udt-last-connected])))
                          m ks-to-pull)
                        (select-keys m ks-to-pull))))))]
          (assert (or (nil? ?pulled) (map? ?pulled)))
          (let [?newly-satisfied
                (when ?pulled
                  (reduce-kv
                   (fn [s client-uuid [?hk-ch _]]
                     (if (or (nil? ?hk-ch)
                             ;; hk-ch may have closed already (`send!` will noop):
                             (not (http-kit/send! ?hk-ch buffered-evs-edn)))
                       s
                       (conj s client-uuid))) #{} ?pulled))
                now-satisfied (into client-uuids-satisfied ?newly-satisfied)]
            ;; (println "now-satisfied:" now-satisfied)
            (when (and (< n nmax-attempts)
                       (some (complement now-satisfied) client-uuids-unsatisfied))
              ;; Allow some time for possible poller reconnects:
              (<! (async/timeout (+ ms-base (rand-int ms-rand))))
              (recur (inc n) now-satisfied))))))))

#+clj
(defn make-channel-socket!
  "Returns `{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
                    connected-uids]}`:
    * ch-recv - core.async channel ; For server-side chsk request router, will
                                   ; receive `event-msg`s from clients.
    * send-fn - (fn [user-id ev])  ; For server>user push
    * ajax-post-fn                - (fn [ring-req]) ; For Ring CSRF-POST, chsk URL
    * ajax-get-or-ws-handshake-fn - (fn [ring-req]) ; For Ring GET,       chsk URL
    * connected-uids ; Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}})

  Common options:
    * user-id-fn       ; (fn [ring-req]) -> unique user-id for server>user push.
    * csrf-token-fn    ; (fn [ring-req]) -> CSRF token for Ajax POSTs.
    * send-buf-ms-ajax ; [1]
    * send-buf-ms-ws   ; [1]

  [1] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."
  [& [{:keys [recv-buf-or-n send-buf-ms-ajax send-buf-ms-ws
              user-id-fn csrf-token-fn]
       :or   {recv-buf-or-n (async/sliding-buffer 1000)
              send-buf-ms-ajax 100
              send-buf-ms-ws   30
              user-id-fn    (fn [ring-req] (get-in ring-req [:session :uid]))
              csrf-token-fn (fn [ring-req]
                              (or (get-in ring-req [:session :csrf-token])
                                  (get-in ring-req [:session :ring.middleware.anti-forgery/anti-forgery-token])
                                  (get-in ring-req [:session "__anti-forgery-token"])))}}]]

  {:pre [(encore/pos-int? send-buf-ms-ajax)
         (encore/pos-int? send-buf-ms-ws)]}

  (let [ch-recv (chan recv-buf-or-n)
        conns_  (atom {:ws   {} ; {<uid> <#{hk-chs}>}
                       :ajax {} ; {<uid> {<client-uuid> [<?hk-ch> <udt-last-connected>]}}
                       })
        connected-uids_ (atom {:ws #{} :ajax #{} :any #{}})
        send-buffers_   (atom {:ws  {} :ajax  {}}) ; {<uid> [<buffered-evs> <#{ev-uuids}>]}

        connect-uid!
        (fn [type uid]
          (let [newly-connected?
                (encore/swap-in! connected-uids_ []
                  (fn [{:keys [ws ajax any] :as old-m}]
                    (let [new-m
                          (case type
                            :ws   {:ws (conj ws uid) :ajax ajax            :any (conj any uid)}
                            :ajax {:ws ws            :ajax (conj ajax uid) :any (conj any uid)})]
                      (encore/swapped new-m
                        (let [old-any (:any old-m)
                              new-any (:any new-m)]
                          (when (and (not (contains? old-any uid))
                                          (contains? new-any uid))
                            :newly-connected))))))]
            newly-connected?))

        upd-connected-uid! ; Useful for atomic disconnects
        (fn [uid]
          (let [newly-disconnected?
                (encore/swap-in! connected-uids_ []
                  (fn [{:keys [ws ajax any] :as old-m}]
                    (let [conns' @conns_
                          any-ws-clients?   (contains? (:ws   conns') uid)
                          any-ajax-clients? (contains? (:ajax conns') uid)
                          any-clients?      (or any-ws-clients?
                                                any-ajax-clients?)
                          new-m
                          {:ws   (if any-ws-clients?   (conj ws   uid) (disj ws   uid))
                           :ajax (if any-ajax-clients? (conj ajax uid) (disj ajax uid))
                           :any  (if any-clients?      (conj any  uid) (disj any  uid))}]
                      (encore/swapped new-m
                        (let [old-any (:any old-m)
                              new-any (:any new-m)]
                          (when (and      (contains? old-any uid)
                                     (not (contains? new-any uid)))
                            :newly-disconnected))))))]
            newly-disconnected?))]

    {:ch-recv ch-recv
     :connected-uids connected-uids_
     :send-fn ; server>user (by uid) push
     (fn [user-id ev & [{:as _opts :keys [flush-send-buffer?]}]]
       (let [uid      user-id
             uid-name (str (or uid "nil"))
             _ (timbre/tracef "Chsk send: (->uid %s) %s" uid-name ev)
             _ (assert-event ev)
             ev-uuid (encore/uuid-str)

             flush-buffer!
             (fn [type]
               (when-let [pulled
                          (encore/swap-in! send-buffers_ [type]
                            (fn [m]
                              ;; Don't actually flush unless the event buffered
                              ;; with _this_ send call is still buffered (awaiting
                              ;; flush). This means that we'll have many (go
                              ;; block) buffer flush calls that'll noop. They're
                              ;;  cheap, and this approach is preferable to
                              ;; alternatives like flush workers.
                              (let [[_ ev-uuids] (get m uid)]
                                (if (contains? ev-uuids ev-uuid)
                                  (encore/swapped (dissoc m uid)
                                                  (get    m uid))
                                  (encore/swapped m nil)))))]
                 (let [[buffered-evs ev-uuids] pulled]
                   (assert (vector? buffered-evs))
                   (assert (set?    ev-uuids))

                   (let [buffered-evs-edn (pr-str buffered-evs)]
                     (case type
                       :ws   (send-buffered-evs>ws-clients!   conns_
                               uid buffered-evs-edn)
                       :ajax (send-buffered-evs>ajax-clients! conns_
                               uid buffered-evs-edn))))))]

         (if (= ev [:chsk/close])
           (do
             (timbre/debugf "Chsk CLOSING: %s" uid-name)

             (when flush-send-buffer?
               (doseq [type [:ws :ajax]]
                 (flush-buffer! type)))

             (doseq [hk-ch      (get-in @conns_ [:ws   uid])] (http-kit/close hk-ch))
             (doseq [hk-ch (->> (get-in @conns_ [:ajax uid])
                                (vals)
                                (map first)
                                (remove nil?))] (http-kit/close hk-ch)))

           (do
             ;; Buffer event
             (doseq [type [:ws :ajax]]
               (encore/swap-in! send-buffers_ [type uid]
                 (fn [old-v]
                   (if-not old-v [[ev] #{ev-uuid}]
                     (let [[buffered-evs ev-uuids] old-v]
                       [(conj buffered-evs ev)
                        (conj ev-uuids     ev-uuid)])))))

             ;;; Flush event buffers after relevant timeouts:
             ;; * May actually flush earlier due to another timeout.
             ;; * We send to _all_ of a uid's connections.
             ;; * Broadcasting is possible but I'd suggest doing it rarely, and
             ;;   only to users we know/expect are actually online.
             (go (when-not flush-send-buffer? (<! (async/timeout send-buf-ms-ws)))
                 (flush-buffer! :ws))
             (go (when-not flush-send-buffer? (<! (async/timeout send-buf-ms-ajax)))
                 (flush-buffer! :ajax)))))

       nil)

     :ajax-post-fn ; Does not participate in `conns_` (has specific req->resp)
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
             (timbre/tracef "Chsk send (ajax reply): cb-dummy-200")
             (http-kit/send! hk-ch (pr-str :chsk/cb-dummy-200))))))

     :ajax-get-or-ws-handshake-fn ; Ajax handshake/poll, or WebSocket handshake
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [uid        (user-id-fn    ring-req)
               uid-name   (str (or uid "nil"))
               csrf-token (csrf-token-fn ring-req)
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
                    :?reply-fn    ?reply-fn}))

               handshake!
               (fn [hk-ch] (http-kit/send! hk-ch
                             (pr-str [:chsk/handshake [uid csrf-token]])))]

           (if (:websocket? ring-req)
             (do ; WebSocket handshake
               (timbre/tracef "New WebSocket channel: %s %s"
                 uid-name (str hk-ch)) ; _Must_ call `str` on ch
               (encore/swap-in! conns_ [:ws uid] (fn [s] (conj (or s #{}) hk-ch)))
               (when (connect-uid! :ws uid)
                 (receive-event-msg!* [:chsk/uidport-open]))

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

               ;; We rely on `on-close` to trigger for _every_ conn:
               (http-kit/on-close hk-ch
                 (fn [status]
                   (encore/swap-in! conns_ [:ws]
                     (fn [m] ; {<uid> <#{hk-chs}>
                       (let [new (disj (get m uid #{}) hk-ch)]
                         (if (empty? new)
                           (dissoc m uid) ; gc
                           (assoc  m uid new)))))

                   ;; (when (upd-connected-uid! uid)
                   ;;   (receive-event-msg!* [:chsk/uidport-close]))

                   (go
                     ;; Allow some time for possible reconnects (sole window
                     ;; refresh, etc.):
                     (<! (async/timeout 5000))

                     ;; Note different (simpler) semantics here than Ajax
                     ;; case since we don't have/want a `udt-disconnected` value.
                     ;; Ajax semantics: 'no reconnect since disconnect+5s'.
                     ;; WS semantics: 'still disconnected after disconnect+5s'.
                     ;;
                     (when (upd-connected-uid! uid)
                       (receive-event-msg!* [:chsk/uidport-close])))))

               (handshake! hk-ch))

             ;; Ajax handshake/poll connection:
             (let [handshake? ; Initial connection for this client?
                   (encore/swap-in! conns_ [:ajax uid client-uuid]
                     (fn [v]
                       (encore/swapped
                         [hk-ch (encore/now-udt)]
                         (nil? v))))]

               (when (connect-uid! :ajax uid)
                 (receive-event-msg!* [:chsk/uidport-open]))

               ;; We rely on `on-close` to trigger for _every_ conn:
               (http-kit/on-close hk-ch
                 (fn [status]
                   (encore/swap-in! conns_ [uid :ajax client-uuid]
                     (fn [[hk-ch udt-last-connected]] [nil udt-last-connected]))

                   (let [udt-disconnected (encore/now-udt)]
                     (go
                       ;; Allow some time for possible poller reconnects:
                       (<! (async/timeout 5000))
                       (let [disconnected?
                             (encore/swap-in! conns_ [:ajax]
                               (fn [m] ; {<uid> {<client-uuid> [<?hk-ch> _]}
                                 (let [[_ ?udt-last-connected]
                                       (get-in m [uid client-uuid])
                                       disconnected?
                                       (and ?udt-last-connected ; Not yet gc'd
                                            (>= udt-disconnected
                                              ?udt-last-connected))]
                                   (if-not disconnected?
                                     (encore/swapped m (not :disconnected))
                                     (let [new (dissoc (get m uid) client-uuid)]
                                       (encore/swapped
                                         (if (empty? new)
                                           (dissoc m uid) ; Gc
                                           (assoc  m uid new))
                                         :disconnected))))))]
                         (when disconnected?
                           (when (upd-connected-uid! uid)
                             (receive-event-msg!* [:chsk/uidport-close]))))))))

               (when handshake?
                 (handshake! hk-ch) ; Client will immediately repoll
                 ))))))}))

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
(defn- pull-unused-cb-fn! [cbs-waiting_ cb-uuid]
  (when cb-uuid
    (first (swap! cbs-waiting_
             (fn [[_ m]] (if-let [f (m cb-uuid)]
                          [f (dissoc m cb-uuid)]
                          [nil m]))))))

#+cljs
(defn- wrap-clj->edn-msg-with-?cb "clj -> [edn ?cb-uuid]"
  [cbs-waiting_ clj ?timeout-ms ?cb-fn]
  (let [?cb-uuid (when ?cb-fn (encore/uuid-str))
        msg      (if-not ?cb-uuid clj {:chsk/clj clj :chsk/cb-uuid ?cb-uuid})
        ;; Note that if pr-str throws, it'll throw before swap!ing cbs-waiting:
        edn     (pr-str msg)]
    (when ?cb-uuid
      (swap! cbs-waiting_
             (fn [[_ m]] [nil (assoc m ?cb-uuid ?cb-fn)]))
      (when ?timeout-ms
        (go (<! (async/timeout ?timeout-ms))
            (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
              (cb-fn* :chsk/timeout)))))
    [edn ?cb-uuid]))

#+cljs
(defprotocol IChSocket
  (chsk-send! [chsk ev] [chsk ev ?timeout-ms ?cb]
    "Sends `[ev-id ?ev-data :as event]` over channel socket connection and returns
     true iff send seems successful.")
  (chsk-make! [chsk]
    "Creates and returns a new channel socket connection, or nil on failure.")
  (chsk-reconnect! [chsk]
    "Re-establishes channel socket connection. Useful for re-authenticating after
    login/logout, etc."))

#+cljs
(defn- merge>chsk-state! [{:keys [chs state_] :as chsk} merge-state]
  (let [[old-state new-state]
        (encore/swap-in! state_ []
          (fn [old-state]
            (let [new-state (merge old-state merge-state)]
              (encore/swapped new-state [old-state new-state]))))]
    (when (not= old-state new-state)
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

#+cljs
(defn- handle-when-handshake! [chsk clj]
  (when (and (vector? clj) ; Nb clj may be callback reply
             (= (first clj) :chsk/handshake))
    (let [[_ [uid csrf-token]] clj]
      (when (str/blank? csrf-token)
        (encore/warnf "Sente warning: NO CSRF TOKEN AVAILABLE"))
      (merge>chsk-state! chsk
        {:open?      true
         :uid        uid
         :csrf-token csrf-token})
      :handled)))

#+cljs ;; Handles reconnects, keep-alives, callbacks:
(defrecord ChWebSocket [url chs socket_ kalive-ms kalive-timer_ kalive-due?_
                        nattempt_
                        cbs-waiting_ ; [dissoc'd-fn {<uuid> <fn> ...}]
                        state_ ; {:type _ :open? _ :uid _ :csrf-token _}
                        ]
  IChSocket
  (chsk-send! [chsk ev] (chsk-send! chsk ev nil nil))
  (chsk-send! [chsk ev ?timeout-ms ?cb]
    ;; (encore/debugf "Chsk send: (%s) %s" (if ?cb "cb" "no cb") ev)
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (wrap-cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (do (encore/warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))
        (let [[edn ?cb-uuid] (wrap-clj->edn-msg-with-?cb
                              cbs-waiting_ ev ?timeout-ms ?cb-fn)]
          (try
            (.send @socket_ edn)
            (reset! kalive-due?_ false)
            :apparent-success
            (catch js/Error e
              (encore/errorf "Chsk send %s" e)
              (when ?cb-uuid
                (let [cb-fn* (or (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)
                                 ?cb-fn)]
                  (cb-fn* :chsk/error)))
              false))))))

  ;; Will auto-recover and handshake:
  (chsk-reconnect! [chsk] (when-let [s @socket_] (.close s)))

  (chsk-make! [chsk]
    (when-let [WebSocket (or (aget js/window "WebSocket")
                             (aget js/window "MozWebSocket"))]
      ((fn connect! []
         (let [retry!
               (fn []
                 (let [nattempt* (swap! nattempt_ inc)]
                   (.clearInterval js/window @kalive-timer_)
                   (encore/warnf "Chsk is closed: will try reconnect (%s)."
                                 nattempt*)
                   (encore/set-exp-backoff-timeout! connect! nattempt*)))]

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
                      (or
                        (and (handle-when-handshake! chsk clj)
                             (reset! nattempt_ 0))
                        (if ?cb-uuid
                          (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
                            (cb-fn clj)
                            (encore/warnf "Cb reply w/o local cb-fn: %s" clj))
                          (let [buffered-evs clj]
                            (receive-buffered-evs! (:recv chs) buffered-evs)))))))
                (aset "onopen"
                  (fn [_ws-ev]
                    (reset! kalive-timer_
                      (.setInterval js/window
                        (fn []
                          (when @kalive-due?_ ; Don't ping unnecessarily
                            (chsk-send! chsk [:chsk/ws-ping]))
                          (reset! kalive-due?_ true))
                        kalive-ms))
                    ;; (merge>chsk-state! chsk
                    ;;   {:open? true}) ; NO, handshake better!
                    ))
                (aset "onclose" ; Fires repeatedly when server is down
                  (fn [_ws-ev] (merge>chsk-state! chsk {:open? false})
                               (retry!))))

            (reset! socket_))

             ;; Couldn't even get a socket:
             (retry!)))))
      chsk)))

#+cljs
(defrecord ChAjaxSocket [url chs timeout ajax-client-uuid curr-xhr_ state_]
  IChSocket
  (chsk-send! [chsk ev] (chsk-send! chsk ev nil nil))
  (chsk-send! [chsk ev ?timeout-ms ?cb]
    ;; (encore/debugf "Chsk send: (%s) %s" (if ?cb "cb" "no cb") ev)
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (wrap-cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
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
               :edn edn :csrf-token (:csrf-token @state_)})}

           (fn ajax-cb [{:keys [content error]}]
             (if error
               (if (= error :timeout)
                 (when ?cb-fn (?cb-fn :chsk/timeout))
                 (do (merge>chsk-state! chsk {:open? false})
                     (when ?cb-fn (?cb-fn :chsk/error))))

               (let [resp-edn content
                     resp-clj (edn/read-string resp-edn)]
                 (if ?cb-fn (?cb-fn resp-clj)
                   (when (not= resp-clj :chsk/cb-dummy-200)
                     (encore/warnf "Cb reply w/o local cb-fn: %s" resp-clj)))
                 (merge>chsk-state! chsk {:open? true})))))

          :apparent-success))))

  ;; Will auto-recover and handshake _iff_ uid has changed since last handshake:
  (chsk-reconnect! [chsk] (when-let [x @curr-xhr_] (.abort x)))

  (chsk-make! [chsk]
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
               (reset! curr-xhr_
                 (encore/ajax-lite url
                   {:method :get :timeout timeout
                    :resp-type :text     ; Prefer to do our own edn reading
                    :params {:_ (encore/now-udt) ; Force uncached resp
                             :ajax-client-uuid ajax-client-uuid}}
                   (fn ajax-cb [{:keys [content error]}]
                     (if error
                       (if (or (= error :timeout)
                               (= error :abort) ; Abort => intentional, not an error
                               ;; It's particularly important that reconnect
                               ;; aborts don't mark a chsk as closed since
                               ;; we've no guarantee that a new handshake will
                               ;; take place to remark as open (e.g. if uid
                               ;; hasn't changed since last handshake).
                               )
                         (async-poll-for-update! 0)
                         (do (merge>chsk-state! chsk {:open? false})
                             (retry!)))

                       ;; The Ajax long-poller is used only for events, never cbs:
                       (let [edn content
                             clj (edn/read-string edn)]
                         (or
                           (handle-when-handshake! chsk clj)
                           (let [buffered-evs clj]
                             (receive-buffered-evs! (:recv chs) buffered-evs)
                             (merge>chsk-state! chsk {:open? true})))
                         (async-poll-for-update! 0)))))))]

         (if-let [pace (aget js/window "Pace")]
           ;; Assumes relevant extern is defined for :advanced mode compilation:
           (.ignore pace ajax-req!) ; Pace.js shouldn't trigger for long-polling
           (ajax-req!))))
     0)
    chsk))

#+cljs
(def default-chsk-url-fn
  "(ƒ [path window-location websocket?]) -> server-side chsk route URL string.

    * path       - As provided to client-side `make-channel-socket!` fn
                   (usu. \"/chsk\").
    * websocket? - True for WebSocket connections, false for Ajax (long-polling)
                   connections.
    * window-location - Map with keys:
      :href     ; \"http://www.example.org:80/foo/bar?q=baz#bang\"
      :protocol ; \"http:\" ; Note the :
      :hostname ; \"example.org\"
      :host     ; \"example.org:80\"
      :pathname ; \"/foo/bar\"
      :search   ; \"?q=baz\"
      :hash     ; \"#bang\"

  Note that the *same* URL is used for: WebSockets, POSTs, GETs. Server-side
  routes should be configured accordingly."
  (fn [path {:as window-location :keys [protocol host pathname]} websocket?]
    (str (if-not websocket? protocol (if (= protocol "https:") "wss:" "ws:"))
         "//" host (or path pathname))))

#+cljs
(defn make-channel-socket!
  "Returns `{:keys [chsk ch-recv send-fn state]}` for new ChWebSocket/ChAjaxSocket:
    * chsk    - The IChSocket implementer. You can usually ignore this.
    * ch-recv - core.async channel that'll receive async (notably server>user)
                events.
    * send-fn - API fn to send client>server[1].
    * state   - Watchable, read-only (atom {:type _ :open? _ :uid _ :csrf-token _}).

  Common options:
    * type         ; e/o #{:auto :ws :ajax}. You'll usually want the default (:auto).
    * ws-kalive-ms ; Ping to keep a WebSocket conn alive if no activity w/in
                   ; given number of milliseconds.
    * lp-kalive-ms ; Ping to keep a long-polling (Ajax) conn alive ''.
    * chsk-url-fn  ; Please see `default-chsk-url-fn` for details."
  [path &
   & [{:keys [type recv-buf-or-n ws-kalive-ms lp-timeout chsk-url-fn]
       :or   {type          :auto
              recv-buf-or-n (async/sliding-buffer 2048) ; Mostly for buffered-evs
              ws-kalive-ms  25000 ; < Heroku 30s conn timeout
              lp-timeout    25000 ; ''
              chsk-url-fn   default-chsk-url-fn}}
      _deprecated-more-opts]]

  {:pre [(#{:ajax :ws :auto} type)]}
  (when (not (nil? _deprecated-more-opts))
    (encore/warnf
     "`make-channel-socket!` fn signature CHANGED with Sente v0.10.0."))

  (let [;; Want _separate_ buffers for state+recv even if we're later merging
        chs {:state    (chan (async/sliding-buffer 1))
             :recv     (chan recv-buf-or-n)
             :internal (chan recv-buf-or-n)}

        window-location (encore/get-window-location)

        chsk
        (or
         (and (not= type :ajax)
              (chsk-make!
                (map->ChWebSocket
                  {:url           (chsk-url-fn path window-location :ws)
                   :chs           chs
                   :socket_       (atom nil)
                   :kalive-ms     ws-kalive-ms
                   :kalive-timer_ (atom nil)
                   :kalive-due?_  (atom true)
                   :nattempt_     (atom 0)
                   :cbs-waiting_  (atom [nil {}])
                   :state_        (atom {:type :ws :open? false})})))

         (and (not= type :ws)
              (let [;; Unchanging over multiple long-poll (re)connects:
                    ajax-client-uuid (encore/uuid-str)]
                (chsk-make!
                  (map->ChAjaxSocket
                    {:url              (chsk-url-fn path window-location (not :ws))
                     :chs              chs
                     :timeout          lp-timeout
                     :ajax-client-uuid ajax-client-uuid
                     :curr-xhr_        (atom nil)
                     :state_           (atom {:type :ajax :open? false})})))))

        ever-opened?_ (atom false)
        state*        (fn [state]
                        (if (or (not (:open? state)) @ever-opened?_)
                          state
                          (do (reset! ever-opened?_ true)
                              (assoc state :first-open? true))))]

    (when chsk
      {:chsk    chsk
       :send-fn (partial chsk-send! chsk)
       :state   (:state_ chsk)
       :ch-recv
       (async/merge
        [(->> (:internal chs) (async/map< (fn [ev] {:pre [(event? ev)]} ev)))
         (->> (:state chs)    (async/map< (fn [state] [:chsk/state (state* state)])))
         (->> (:recv  chs)    (async/map< (fn [ev]    [:chsk/recv  ev])))])})))

;;;; Routers

#+clj
(defn start-chsk-router-loop! [event-msg-handler ch]
  (let [ctrl-ch (chan)]
    (go-loop []
      (when-not ; nil or ::stop
        (try
          (let [[v p] (async/alts! [ch ctrl-ch])]
            (if (identical? p ctrl-ch) ::stop
              (let [event-msg v]
                (try
                  (timbre/tracef "Event-msg: %s" event-msg)
                  (do (event-msg-handler event-msg ch) nil)
                  (catch Throwable t
                    (timbre/errorf t "Chsk-router-loop handling error: %s" event-msg))))))
          (catch Throwable t
            (timbre/errorf t "Chsk-router-loop channel error!")))
        (recur)))
    (fn stop! [] (async/close! ctrl-ch))))

#+cljs
(defn start-chsk-router-loop! [event-handler ch]
  (let [ctrl-ch (chan)]
    (go-loop []
      (let [[v p] (async/alts! [ch ctrl-ch])]
        (if (identical? p ctrl-ch) ::stop
          (let [[id data :as event] v]
            ;; Provide ch to handler to allow event injection back into loop:
            (event-handler event ch)  ; Allow errors to throw
            (recur)))))
    (fn stop! [] (async/close! ctrl-ch))))
