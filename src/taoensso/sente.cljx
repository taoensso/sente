(ns taoensso.sente
  "Channel sockets. Otherwise known as The Shiz.

      Protocol  | client>server | client>server ?+ ack/reply | server>user[1] push
    * WebSockets:       ✓              [2]                           ✓
    * Ajax:            [3]              ✓                           [4]

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
    * pstr  - Packed string. Arbitrary Clojure data serialized as a string (e.g.
              edn) for client<->server comms.

  Special messages (implementation detail):
    * Callback replies: :chsk/closed, :chsk/timeout, :chsk/error.
    * Client-side events:
        [:chsk/handshake [<?uid> <?csrf-token>]],
        [:chsk/ws-ping],
        [:chsk/state <new-state>],
        [:chsk/recv <[buffered-evs]>] ; server>user push

    * Server-side events:
        [:chsk/bad-package <packed-str>], ; was :chsk/bad-edn
        [:chsk/bad-event <chsk-event>],
        [:chsk/uidport-open],
        [:chsk/uidport-close].

    * Callback wrapping: [<clj> <?cb-uuid>] for [2],[3].

  Notable implementation details:
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
  (:require
   [clojure.string     :as str]
   [clojure.core.async :as async :refer (<! <!! >! >!! put! chan
                                         go go-loop)]
   ;; [clojure.tools.reader.edn :as edn]
   [org.httpkit.server        :as http-kit]
   [taoensso.encore           :as encore :refer (have? have have-in)]
   [taoensso.timbre           :as timbre]
   [taoensso.sente.interfaces :as interfaces])

  #+cljs
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async :refer (<! >! put! chan)]
   ;; [cljs.reader  :as edn]
   [taoensso.encore :as encore :refer (format)]
   [taoensso.sente.interfaces :as interfaces])

  #+cljs
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]
   [taoensso.encore        :as encore :refer (have? have have-in)]))

;;;; Logging

#+clj  (refer 'taoensso.timbre :only '(tracef debugf infof warnf errorf))
#+cljs (do (def tracef encore/tracef)
           (def debugf encore/debugf)
           (def infof  encore/infof)
           (def warnf  encore/warnf)
           (def errorf encore/errorf))

(defn set-logging-level! [level]
  #+clj  (timbre/set-level!           level)
  #+cljs (reset! encore/logging-level level))

;; (set-logging-level! :trace) ; For debugging

;;;; Ajax

#+cljs
(def ajax-call
  "Alpha - subject to change.
  Simple+lightweight Ajax via Google Closure. Returns nil, or the xhr instance.
  Ref. https://developers.google.com/closure/library/docs/xhrio.

  (ajax-call \"/my-post-route\"
    {:method     :post
     :params     {:username \"Rich Hickey\"
                  :type     \"Awesome\"}
     :headers    {\"Foo\" \"Bar\"}
     :resp-type  :text
     :timeout-ms 7000}
    (fn async-callback [resp-map]
      (let [{:keys [?status ?error ?content ?content-type]} resp-map]
        ;; ?status - 200, 404, ..., or nil on no response
        ;; ?error  - e/o #{:xhr-pool-depleted :exception :http-error :abort
        ;;                 :timeout <http-error-status> nil}
        (js/alert (str \"Ajax response: \" resp-map)))))"
  encore/ajax-lite)

;;;; Events
;; * Clients & server both send `event`s and receive (i.e. route) `event-msg`s.

(defn- validate-event [x]
  (cond
    (not (vector? x))        :wrong-type
    (not (#{1 2} (count x))) :wrong-length
    :else (let [[ev-id _] x]
            (cond (not (keyword? ev-id))  :wrong-id-type
                  (not (namespace ev-id)) :unnamespaced-id
                  :else nil))))

(defn event? "Valid [ev-id ?ev-data] form?" [x] (nil? (validate-event x)))

(defn as-event [x] (if (event? x) x [:chsk/bad-event x]))

(defn assert-event [x]
  (when-let [?err (validate-event x)]
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

(defn- chan? [x]
  #+clj  (instance? clojure.core.async.impl.channels.ManyToManyChannel x)
  #+cljs (instance?    cljs.core.async.impl.channels.ManyToManyChannel x))

(defn event-msg? [x]
  #+cljs
  (and
    (map? x)
    (encore/keys= x #{:ch-recv :send-fn :state :event :id :?data})
    (let [{:keys [ch-recv send-fn state event]} x]
      (and
        (chan?        ch-recv)
        (ifn?         send-fn)
        (encore/atom? state)
        (event?       event))))

  #+clj
  (and
    (map? x)
    (encore/keys= x #{:ch-recv :send-fn :connected-uids
                      :client-uuid :ring-req :event :id :?data :?reply-fn})
    (let [{:keys [ch-recv send-fn connected-uids
                  client-uuid ring-req event ?reply-fn]} x]
      (and
        (chan?              ch-recv)
        (ifn?               send-fn)
        (encore/atom?       connected-uids)
        ;;
        ;; Browser-tab / device identifier, set by client (ajax) or server (ws):
        (encore/nblank-str? client-uuid)
        (map?               ring-req)
        (event?             event)
        (or (nil? ?reply-fn) (ifn? ?reply-fn))))))

#+clj
(defn- put-event-msg>ch-recv!
  "All server-side `event-msg`s go through this."
  [ch-recv {:as ev-msg :keys [event ?reply-fn]}]
  (let [[ev-id ev-?data :as valid-event] (as-event event)
        ;; ?reply-fn (if (ifn? ?reply-fn) ?reply-fn
        ;;             ^:dummy-reply-fn ; Useful for routers, etc.
        ;;             (fn [resp-clj]
        ;;               (warnf "Trying to reply to non-cb event: %s (with reply %s)"
        ;;                   valid-event resp-clj)))
        ev-msg* (merge ev-msg {:event     valid-event
                               :?reply-fn ?reply-fn
                               :id        ev-id
                               :?data     ev-?data})]
    (if-not (event-msg? ev-msg*)
      (warnf "Bad ev-msg: %s" ev-msg) ; Log 'n drop
      (put! ch-recv ev-msg*))))

#+cljs
(defn cb-success? "Note that cb reply need _not_ be `event` form!"
  [cb-reply-clj] (not (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply-clj)))

;;;; Packing
;; * Client<->server payloads are arbitrary Clojure vals (cb replies or events).
;; * Payloads are packed for client<->server transit.
;; * Packing includes ->str encoding, and may incl. wrapping to carry cb info.

(defn- unpack* "pstr->clj" [packer pstr]
  (try
    (assert (string? pstr))
    (interfaces/unpack packer pstr)
    (catch #+clj Throwable #+cljs :default t
      (debugf "Bad package: %s (%s)" pstr t)
      #+clj  [:chsk/bad-package pstr]
      #+cljs (throw t) ; Let client rethrow on bad pstr from server
      )))

(defn- with-?meta [x ?m] (if (seq ?m) (with-meta x ?m) x))
(defn- pack* "clj->prefixed-pstr"
  ([packer ?packer-meta clj]
     (str "-" ; => Unwrapped (no cb metadata)
       (interfaces/pack packer (with-?meta clj ?packer-meta))))

  ([packer ?packer-meta clj ?cb-uuid]
     (let [;;; Keep wrapping as light as possible:
           ?cb-uuid    (if (= ?cb-uuid :ajax-cb) 0 ?cb-uuid)
           wrapped-clj (if ?cb-uuid [clj ?cb-uuid] [clj])]
       (str "+" ; => Wrapped (cb metadata)
         (interfaces/pack packer (with-?meta wrapped-clj ?packer-meta))))))

(defn- pack [& args]
  (let [pstr (apply pack* args)]
    (tracef "Packing: %s -> %s" args pstr)
    pstr))

(defn- unpack "prefixed-pstr->[clj ?cb-uuid]"
  [packer prefixed-pstr]
  (assert (string? prefixed-pstr))
  (let [prefix   (encore/substr prefixed-pstr 0 1)
        pstr     (encore/substr prefixed-pstr 1)
        clj      (unpack* packer pstr) ; May be un/wrapped
        wrapped? (case prefix "-" false "+" true)
        [clj ?cb-uuid] (if wrapped? clj [clj nil])
        ?cb-uuid (if (= 0 ?cb-uuid) :ajax-cb ?cb-uuid)]
    (tracef "Unpacking: %s -> %s" prefixed-pstr [clj ?cb-uuid])
    [clj ?cb-uuid]))

(comment
  (do (require '[taoensso.sente.packers.transit :as transit])
      (def edn-packer   interfaces/edn-packer)
      (def flexi-packer (transit/get-flexi-packer)))
  (unpack edn-packer   (pack edn-packer   nil          "hello"))
  (unpack flexi-packer (pack flexi-packer nil          "hello"))
  (unpack flexi-packer (pack flexi-packer {}           [:foo/bar {}] "my-cb-uuid"))
  (unpack flexi-packer (pack flexi-packer {:json true} [:foo/bar {}] "my-cb-uuid"))
  (unpack flexi-packer (pack flexi-packer {}           [:foo/bar {}] :ajax-cb)))

;;;; Server API

#+clj (declare ^:private send-buffered-evs>ws-clients!
               ^:private send-buffered-evs>ajax-clients!)

#+clj
(defn make-channel-socket!
  "Returns a map with keys:
    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
    :send-fn ; (fn [user-id ev] for server>user push.
    :ajax-post-fn                ; (fn [ring-req]  for Ring CSRF-POST + chsk URL.
    :ajax-get-or-ws-handshake-fn ; (fn [ring-req]) for Ring GET + chsk URL.
    :connected-uids ; Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}}).

  Common options:
    :user-id-fn       ; (fn [ring-req]) -> unique user-id for server>user push.
    :csrf-token-fn    ; (fn [ring-req]) -> CSRF token for Ajax POSTs.
    :send-buf-ms-ajax ; [1]
    :send-buf-ms-ws   ; [1]
    :packer           ; :edn (default), or an IPacker implementation (experimental).

  [1] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."
  [& [{:keys [recv-buf-or-n send-buf-ms-ajax send-buf-ms-ws
              user-id-fn csrf-token-fn packer]
       :or   {recv-buf-or-n (async/sliding-buffer 1000)
              send-buf-ms-ajax 100
              send-buf-ms-ws   30
              user-id-fn    (fn [ring-req] (get-in ring-req [:session :uid]))
              csrf-token-fn (fn [ring-req]
                              (or (get-in ring-req [:session :csrf-token])
                                  (get-in ring-req [:session :ring.middleware.anti-forgery/anti-forgery-token])
                                  (get-in ring-req [:session "__anti-forgery-token"])))
              packer :edn}}]]

  {:pre [(encore/pos-int? send-buf-ms-ajax)
         (encore/pos-int? send-buf-ms-ws)]}

  (let [packer  (interfaces/coerce-packer packer)
        ch-recv (chan recv-buf-or-n)
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
            newly-disconnected?))

        send-fn ; server>user (by uid) push
        (fn [user-id ev & [{:as opts :keys [flush?]}]]
          (let [uid      user-id
                uid-name (str (or uid "nil"))
                _ (tracef "Chsk send: (->uid %s) %s" uid-name ev)
                _ (assert-event ev)
                ev-uuid (encore/uuid-str)

                flush-buffer!
                (fn [type]
                  (when-let
                      [pulled
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

                      (let [packer-metas         (map meta buffered-evs)
                            combined-packer-meta (reduce merge {} packer-metas)
                            buffered-evs-ppstr   (pack packer
                                                   combined-packer-meta
                                                   buffered-evs)]
                        (tracef "buffered-evs-ppstr: %s (with meta %s)"
                          buffered-evs-ppstr combined-packer-meta)
                        (case type
                          :ws   (send-buffered-evs>ws-clients!   conns_
                                  uid buffered-evs-ppstr)
                          :ajax (send-buffered-evs>ajax-clients! conns_
                                  uid buffered-evs-ppstr))))))]

            (if (= ev [:chsk/close]) ; Currently undocumented
              (do
                (debugf "Chsk closing (client may reconnect): %s" uid-name)
                (when flush?
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
                (go (when-not flush? (<! (async/timeout send-buf-ms-ws)))
                  (flush-buffer! :ws))
                (go (when-not flush? (<! (async/timeout send-buf-ms-ajax)))
                  (flush-buffer! :ajax)))))

          ;; Server-side send is async so nothing useful to return (currently
          ;; undefined):
          nil)

        ev-msg-const {:ch-recv        ch-recv
                      :send-fn        send-fn
                      :connected-uids connected-uids_}]

    {:ch-recv        ch-recv
     :send-fn        send-fn
     :connected-uids connected-uids_

     :ajax-post-fn ; Does not participate in `conns_` (has specific req->resp)
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [ppstr (get-in ring-req [:params :ppstr])
               [clj has-cb?] (unpack packer ppstr)]

           (put-event-msg>ch-recv! ch-recv
             (merge ev-msg-const
               {;; Currently unused for non-lp POSTs, but necessary for `event-msg?`:
                :client-uuid "dummy-ajax-post-fn-uuid" ; (encore/uuid-str)
                :ring-req    ring-req
                :event       clj
                :?reply-fn
                (when has-cb?
                  (fn reply-fn [resp-clj] ; Any clj form
                    (tracef "Chsk send (ajax reply): %s" resp-clj)
                    (let [resp-ppstr (pack packer (meta resp-clj) resp-clj)]
                      ;; true iff apparent success:
                      (http-kit/send! hk-ch resp-ppstr))))}))

           (when-not has-cb?
             (tracef "Chsk send (ajax reply): dummy-cb-200")
             (http-kit/send! hk-ch
               (let [ppstr (pack packer nil :chsk/dummy-cb-200)]
                 ppstr))))))

     :ajax-get-or-ws-handshake-fn ; Ajax handshake/poll, or WebSocket handshake
     (fn [ring-req]
       (http-kit/with-channel ring-req hk-ch
         (let [uid        (user-id-fn    ring-req)
               csrf-token (csrf-token-fn ring-req)
               uid-name   (str (or uid "nil"))
               client-uuid  ; Browser-tab / device identifier
               (str uid "-" ; Security measure (can't be controlled by client)
                 (or (get-in ring-req [:params :ajax-client-uuid])
                     (encore/uuid-str 8) ; Reduced len (combined with uid)
                     ))

               receive-event-msg! ; Partial
               (fn [event & [?reply-fn]]
                 (put-event-msg>ch-recv! ch-recv
                   (merge ev-msg-const
                     {:client-uuid client-uuid ; Fixed (constant) with handshake
                      :ring-req    ring-req    ; ''
                      :event       event
                      :?reply-fn   ?reply-fn})))

               handshake!
               (fn [hk-ch]
                 (tracef "Handshake!")
                 (http-kit/send! hk-ch
                   (let [ppstr (pack packer nil [:chsk/handshake [uid csrf-token]])]
                     ppstr)))]

           (if (:websocket? ring-req)
             (do ; WebSocket handshake
               (tracef "New WebSocket channel: %s (%s)"
                 uid-name (str hk-ch)) ; _Must_ call `str` on ch
               (encore/swap-in! conns_ [:ws uid] (fn [s] (conj (or s #{}) hk-ch)))
               (when (connect-uid! :ws uid)
                 (receive-event-msg! [:chsk/uidport-open]))

               (http-kit/on-receive hk-ch
                 (fn [req-ppstr]
                   (let [[clj ?cb-uuid] (unpack packer req-ppstr)]
                     (receive-event-msg! clj ; Should be ev
                       (when ?cb-uuid
                         (fn reply-fn [resp-clj] ; Any clj form
                           (tracef "Chsk send (ws reply): %s" resp-clj)
                           (let [resp-ppstr (pack packer (meta resp-clj)
                                              resp-clj ?cb-uuid)]
                             ;; true iff apparent success:
                             (http-kit/send! hk-ch resp-ppstr))))))))

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
                   ;;   (receive-event-msg! [:chsk/uidport-close]))

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
                       (receive-event-msg! [:chsk/uidport-close])))))

               (handshake! hk-ch))

             ;; Ajax handshake/poll connection:
             (let [handshake? ; Initial connection for this client?
                   (encore/swap-in! conns_ [:ajax uid client-uuid]
                     (fn [v]
                       (encore/swapped
                         [hk-ch (encore/now-udt)]
                         (nil? v))))]

               (when (connect-uid! :ajax uid)
                 (receive-event-msg! [:chsk/uidport-open]))

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
                             (receive-event-msg! [:chsk/uidport-close]))))))))

               (when handshake?
                 (handshake! hk-ch) ; Client will immediately repoll
                 ))))))}))

#+clj
(defn- send-buffered-evs>ws-clients!
  "Actually pushes buffered events (as packed-str) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-pstr]
  (tracef "send-buffered-evs>ws-clients!: %s" buffered-evs-pstr)
  (doseq [hk-ch (get-in @conns_ [:ws uid])]
    (http-kit/send! hk-ch buffered-evs-pstr)))

#+clj
(defn- send-buffered-evs>ajax-clients!
  "Actually pushes buffered events (as packed-str) to all uid's Ajax conns.
  Allows some time for possible Ajax poller reconnects."
  [conns_ uid buffered-evs-pstr & [{:keys [nmax-attempts ms-base ms-rand]
                                    ;; <= 7 attempts at ~135ms ea = 945ms
                                    :or   {nmax-attempts 7
                                           ms-base       90
                                           ms-rand       90}}]]
  (comment (* 7 (+ 90 (/ 90 2.0))))
  (let [;; All connected/possibly-reconnecting client uuids:
        client-uuids-unsatisfied (keys (get-in @conns_ [:ajax uid]))]
    (when-not (empty? client-uuids-unsatisfied)
      ;; (tracef "client-uuids-unsatisfied: %s" client-uuids-unsatisfied)
      (go-loop [n 0 client-uuids-satisfied #{}]
        (let [?pulled ; nil or {<client-uuid> [<?hk-ch> <udt-last-connected>]}
              (encore/swap-in! conns_ [:ajax uid]
                (fn [m] ; {<client-uuid> [<?hk-ch> <udt-last-connected>]}
                  (let [ks-to-pull (remove client-uuids-satisfied (keys m))]
                    ;; (tracef "ks-to-pull: %s" ks-to-pull)
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
                             (not (http-kit/send! ?hk-ch buffered-evs-pstr)))
                       s
                       (conj s client-uuid))) #{} ?pulled))
                now-satisfied (into client-uuids-satisfied ?newly-satisfied)]
            ;; (tracef "now-satisfied: %s" now-satisfied)
            (when (and (< n nmax-attempts)
                       (some (complement now-satisfied) client-uuids-unsatisfied))
              ;; Allow some time for possible poller reconnects:
              (<! (async/timeout (+ ms-base (rand-int ms-rand))))
              (recur (inc n) now-satisfied))))))))

;;;; Client API

#+cljs
(defprotocol IChSocket
  (chsk-init!      [chsk] "Implementation detail.")
  (chsk-destroy!   [chsk] "Kills socket, stops auto-reconnects.")
  (chsk-reconnect! [chsk] "Drops connection, allows auto-reconnect. Useful for reauthenticating after login/logout.")
  (chsk-send!*     [chsk ev opts] "Implementation detail."))

#+cljs
(defn chsk-send!
  "Sends `[ev-id ev-?data :as event]`, returns true on apparent success."
  ([chsk ev]                 (chsk-send! chsk ev {}))
  ([chsk ev ?timeout-ms ?cb] (chsk-send! chsk ev {:timeout-ms ?timeout-ms
                                                  :cb         ?cb}))
  ([chsk ev opts]
     (tracef "Chsk send: (%s) %s" (assoc opts :cb (boolean (:cb opts))) ev)
     (chsk-send!* chsk ev opts)))

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
(defn- pull-unused-cb-fn! [cbs-waiting_ ?cb-uuid]
  (when ?cb-uuid
    (first (swap! cbs-waiting_
             (fn [[_ m]] (if-let [f (m ?cb-uuid)]
                          [f (dissoc m ?cb-uuid)]
                          [nil m]))))))

#+cljs
(defn- merge>chsk-state! [{:keys [chs state_] :as chsk} merge-state]
  (let [[old-state new-state]
        (encore/swap-in! state_ []
          (fn [old-state]
            (let [new-state (merge old-state merge-state)]
              (encore/swapped new-state [old-state new-state]))))]
    (when (not= old-state new-state)
      ;; (debugf "Chsk state change: %s" new-state)
      (put! (:state chs) new-state)
      new-state)))

#+cljs
(defn- cb-chan-as-fn
  "Experimental, undocumented. Allows a core.async channel to be provided
  instead of a cb-fn. The channel will receive values of form
  [<event-id>.cb <reply>]."
  [?cb ev]
  (if (or (nil? ?cb) (ifn? ?cb)) ?cb
    (do (assert (chan? ?cb))
        (assert-event ev)
        (let [[ev-id _] ev
              cb-ch ?cb]
          (fn [reply]
            (put! cb-ch [(keyword (str (encore/fq-name ev-id) ".cb"))
                         reply]))))))

#+cljs
(defn- receive-buffered-evs! [ch-recv clj]
  (tracef "receive-buffered-evs!: %s" clj)
  (assert (vector? clj))
  (let [buffered-evs clj]
    (doseq [ev buffered-evs]
      (assert-event ev)
      (put! ch-recv ev))))

#+cljs
(defn- handle-when-handshake! [chsk clj]
  (tracef "handle-when-handshake!: %s" clj)
  (when (and (vector? clj) ; Nb clj may be callback reply
             (= (first clj) :chsk/handshake))
    (let [[_ [uid csrf-token]] clj]
      (when (str/blank? csrf-token)
        (warnf "Sente warning: NO CSRF TOKEN AVAILABLE"))
      (merge>chsk-state! chsk
        {:open?      true
         :uid        uid
         :csrf-token csrf-token})
      :handled)))

#+cljs
(defn set-exp-backoff-timeout! [nullary-f & [nattempt]]
  (.setTimeout js/window nullary-f (encore/exp-backoff (or nattempt 0))))

#+cljs ;; Handles reconnects, keep-alives, callbacks:
(defrecord ChWebSocket
    [url chs socket_ kalive-ms kalive-timer_ kalive-due?_ nattempt_
     cbs-waiting_ ; [dissoc'd-fn {<uuid> <fn> ...}]
     state_       ; {:type _ :open? _ :uid _ :csrf-token _ :destroyed? _}
     packer       ; IPacker
     ]

  IChSocket
  (chsk-send!* [chsk ev {:as opts ?timeout-ms :timeout-ms ?cb :cb :keys [flush?]}]
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (do (warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))

        ;; TODO Buffer before sending (but honor `:flush?`)
        (let [?cb-uuid (when ?cb-fn
                         (encore/uuid-str 6)) ; Mini uuid (short-lived, per client)
              ppstr    (pack packer (meta ev) ev ?cb-uuid)]

          (when ?cb-uuid
            (swap! cbs-waiting_
              (fn [[_ m]] [nil (assoc m ?cb-uuid ?cb-fn)]))
            (when ?timeout-ms
              (go (<! (async/timeout ?timeout-ms))
                (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
                  (cb-fn* :chsk/timeout)))))

          (try
            (.send @socket_ ppstr)
            (reset! kalive-due?_ false)
            :apparent-success
            (catch js/Error e
              (errorf "Chsk send error: %s" e)
              (when ?cb-uuid
                (let [cb-fn* (or (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)
                                 ?cb-fn)]
                  (cb-fn* :chsk/error)))
              false))))))

  (chsk-reconnect!  [chsk] (when-let [s @socket_] (.close s)))
  (chsk-destroy!    [chsk]
    (merge>chsk-state! chsk {:destroyed? true :open? false})
    (chsk-reconnect!   chsk))

  (chsk-init! [chsk]
    (when-let [WebSocket (or (aget js/window "WebSocket")
                             (aget js/window "MozWebSocket"))]
      ((fn connect! []
         (when-not (:destroyed? @state_)
           (let [retry!
                 (fn []
                   (let [nattempt* (swap! nattempt_ inc)]
                     (.clearInterval js/window @kalive-timer_)
                     (warnf "Chsk is closed: will try reconnect (%s)." nattempt*)
                     (set-exp-backoff-timeout! connect! nattempt*)))]

             (if-let [socket (try (WebSocket. url)
                                  (catch js/Error e
                                    (errorf "WebSocket js/Error: %s" e)
                                    nil))]
               (reset! socket_
                 (doto socket
                   (aset "onerror" (fn [ws-ev] (errorf "WebSocket error: %s" ws-ev)))
                   (aset "onmessage" ; Nb receives both push & cb evs!
                     (fn [ws-ev]
                       (let [;; Nb may or may NOT satisfy `event?` since we also
                             ;; receive cb replies here! This is actually why
                             ;; we prefix our pstrs to indicate whether they're
                             ;; wrapped or not.
                             ppstr (aget ws-ev "data")
                             [clj ?cb-uuid] (unpack packer ppstr)]
                         ;; (assert-event clj) ;; NO!
                         (or
                           (and (handle-when-handshake! chsk clj)
                                (reset! nattempt_ 0))
                           (if ?cb-uuid
                             (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting_
                                              ?cb-uuid)]
                               (cb-fn clj)
                               (warnf "Cb reply w/o local cb-fn: %s" clj))
                             (let [buffered-evs clj]
                               (receive-buffered-evs! (:<server chs)
                                 buffered-evs)))))))

                   (aset "onopen"
                     (fn [_ws-ev]
                       (reset! kalive-timer_
                         (.setInterval js/window
                           (fn []
                             (when @kalive-due?_ ; Don't ping unnecessarily
                               (chsk-send! chsk [:chsk/ws-ping]))
                             (reset! kalive-due?_ true))
                           kalive-ms))
                       ;; NO, handshake better!:
                       ;; (merge>chsk-state! chsk {:open? true})
                       ))

                   (aset "onclose" ; Fires repeatedly when server is down
                     (fn [_ws-ev] (merge>chsk-state! chsk {:open? false})
                       (retry!)))))

               ;; Couldn't even get a socket:
               (retry!))))))
      chsk)))

#+cljs
(defrecord ChAjaxSocket [url chs timeout-ms ajax-client-uuid curr-xhr_ state_ packer]
  IChSocket
  (chsk-send!* [chsk ev {:as opts ?timeout-ms :timeout-ms ?cb :cb :keys [flush?]}]
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (do (warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))

        ;; TODO Buffer before sending (but honor `:flush?`)
        (do
          (ajax-call url
           {:method :post :timeout-ms ?timeout-ms
            :resp-type :text ; We'll do our own pstr decoding
            :params
            (let [ppstr (pack packer (meta ev) ev (when ?cb-fn :ajax-cb))]
              {:_          (encore/now-udt) ; Force uncached resp
               :ppstr      ppstr
               :csrf-token (:csrf-token @state_)})}

           (fn ajax-cb [{:keys [?error ?content]}]
             (if ?error
               (if (= ?error :timeout)
                 (when ?cb-fn (?cb-fn :chsk/timeout))
                 (do (merge>chsk-state! chsk {:open? false})
                     (when ?cb-fn (?cb-fn :chsk/error))))

               (let [content      ?content
                     resp-ppstr   content
                     [resp-clj _] (unpack packer resp-ppstr)]
                 (if ?cb-fn (?cb-fn resp-clj)
                   (when (not= resp-clj :chsk/dummy-cb-200)
                     (warnf "Cb reply w/o local cb-fn: %s" resp-clj)))
                 (merge>chsk-state! chsk {:open? true})))))

          :apparent-success))))

  (chsk-reconnect!  [chsk] (when-let [x @curr-xhr_] (.abort x)))
  (chsk-destroy!    [chsk]
    (merge>chsk-state! chsk {:destroyed? true :open? false})
    (chsk-reconnect!   chsk))

  (chsk-init! [chsk]
    ((fn async-poll-for-update! [nattempt]
       (tracef "async-poll-for-update!")
       (when-not (:destroyed? @state_)
         (let [retry!
               (fn []
                 (let [nattempt* (inc nattempt)]
                   (warnf "Chsk is closed: will try reconnect (%s)." nattempt*)
                   (set-exp-backoff-timeout!
                     (partial async-poll-for-update! nattempt*)
                     nattempt*)))

               ajax-req! ; Just for Pace wrapping below
               (fn []
                 (reset! curr-xhr_
                   (ajax-call url
                     {:method :get :timeout-ms timeout-ms
                      :resp-type :text ; Prefer to do our own pstr reading
                      :params {:_ (encore/now-udt) ; Force uncached resp
                               :ajax-client-uuid ajax-client-uuid}}
                     (fn ajax-cb [{:keys [?error ?content]}]
                       (if ?error
                         (if (or (= ?error :timeout)
                                 (= ?error :abort) ; Abort => intentional, not err
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
                         (let [content ?content
                               ppstr   content
                               [clj _] (unpack packer ppstr)]
                           (or
                             (handle-when-handshake! chsk clj)
                             (let [buffered-evs clj]
                               (receive-buffered-evs! (:<server chs) buffered-evs)
                               (merge>chsk-state! chsk {:open? true})))
                           (async-poll-for-update! 0)))))))]

           ;; TODO Make this pluggable
           (if-let [pace (aget js/window "Pace")]
             ;; Assumes relevant extern is defined for :advanced mode compilation:
             (.ignore pace ajax-req!) ; Pace.js shouldn't trigger for long-polling
             (ajax-req!)))))
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
  "Returns a map with keys:
    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
             ; May `put!` (inject) arbitrary `event`s to this channel.
    :send-fn ; (fn [event & [?timeout-ms ?cb-fn]]) for client>server send.
    :state   ; Watchable, read-only (atom {:type _ :open? _ :uid _ :csrf-token _}).
    :chsk    ; IChSocket implementer. You can usu. ignore this.

  Common options:
    :type         ; e/o #{:auto :ws :ajax}. You'll usually want the default (:auto).
    :ws-kalive-ms ; Ping to keep a WebSocket conn alive if no activity w/in given
                  ; number of milliseconds.
    :lp-kalive-ms ; Ping to keep a long-polling (Ajax) conn alive ''.
    :chsk-url-fn  ; Please see `default-chsk-url-fn` for details.
    :packer       ; :edn (default), or an IPacker implementation (experimental)."
  [path &
   & [{:keys [type recv-buf-or-n ws-kalive-ms lp-timeout-ms chsk-url-fn packer]
       :as   opts
       :or   {type          :auto
              recv-buf-or-n (async/sliding-buffer 2048) ; Mostly for buffered-evs
              ws-kalive-ms  25000 ; < Heroku 30s conn timeout
              lp-timeout-ms 25000 ; ''
              chsk-url-fn   default-chsk-url-fn
              packer        :edn}}
      _deprecated-more-opts]]

  {:pre [(#{:ajax :ws :auto} type)]}
  (when (not (nil? _deprecated-more-opts))
    (warnf "`make-channel-socket!` fn signature CHANGED with Sente v0.10.0."))
  (when (contains? opts :lp-timeout)
    (warnf ":lp-timeout opt has CHANGED; please use :lp-timout-ms."))

  (let [packer (interfaces/coerce-packer packer)
        window-location (encore/get-window-location)
        private-chs {:state    (chan (async/sliding-buffer 1))
                     :internal (chan (async/sliding-buffer 10))
                     :<server  (chan recv-buf-or-n)}

        ever-opened?_ (atom false)
        state*        (fn [state]
                        (if (or (not (:open? state)) @ever-opened?_) state
                          (do (reset! ever-opened?_ true)
                              (assoc state :first-open? true))))

        ;;; TODO
        ;; * map< is deprecated in favour of transducers.
        ;; * Maybe allow a flag to skip wrapping of :chsk/recv events?.
        public-ch-recv
        (async/merge
          [(:internal private-chs)
           (async/map< (fn [state] [:chsk/state (state* state)]) (:state private-chs))
           (async/map< (fn [ev]    [:chsk/recv  ev])          (:<server  private-chs))]
          ;; recv-buf-or-n ; Seems to be malfunctioning
          )

        chsk
        (or
         (and (not= type :ajax)
              (chsk-init!
                (map->ChWebSocket
                  {:url           (chsk-url-fn path window-location :ws)
                   :chs           private-chs
                   :packer        packer
                   :socket_       (atom nil)
                   :kalive-ms     ws-kalive-ms
                   :kalive-timer_ (atom nil)
                   :kalive-due?_  (atom true)
                   :nattempt_     (atom 0)
                   :cbs-waiting_  (atom [nil {}])
                   :state_        (atom {:type :ws :open? false
                                         :destroyed? false})})))

         (and (not= type :ws)
              (let [;; Unchanging over multiple long-poll (re)connects:
                    ajax-client-uuid (encore/uuid-str)]
                (chsk-init!
                  (map->ChAjaxSocket
                    {:url              (chsk-url-fn path window-location (not :ws))
                     :chs              private-chs
                     :packer           packer
                     :timeout-ms       lp-timeout-ms
                     :ajax-client-uuid ajax-client-uuid
                     :curr-xhr_        (atom nil)
                     :state_           (atom {:type :ajax :open? false
                                              :destroyed? false})})))))

        send-fn (partial chsk-send! chsk)

        public-ch-recv
        (async/map<
          ;; All client-side `event-msg`s go through this (allows client to
          ;; inject arbitrary synthetic events into router for handling):
          (fn ev->ev-msg [ev]
            (let [[ev-id ev-?data :as ev] (as-event ev)]
              {:ch-recv  public-ch-recv
               :send-fn  send-fn
               :state    (:state_ chsk)
               :event    ev
               :id       ev-id
               :?data    ev-?data}))
          public-ch-recv)]

    (when chsk
      {:chsk    chsk
       :ch-recv public-ch-recv ; `ev`s->`ev-msg`s ch
       :send-fn send-fn
       :state   (:state_ chsk)})))

;;;; Router wrapper

(defn start-chsk-router!
  "Creates a go-loop to call `(event-msg-handler <event-msg>)` and returns a
  `(fn stop! [])`. Catches & logs errors. Advanced users may choose to instead
  write their own loop against `ch-recv`."
  [ch-recv event-msg-handler & [{:as opts :keys [trace-evs?]}]]
  (let [ch-ctrl (chan)]
    (go-loop []
      (when-not
        (encore/kw-identical? ::stop
          (try
            (let [[v p] (async/alts! [ch-recv ch-ctrl])]
              (if (encore/kw-identical? p ch-ctrl) ::stop
                  (let [{:as event-msg :keys [event]} v]
                  (try
                    (when trace-evs?
                      (tracef "Pre-handler event: %s" event))
                    (if-not (event-msg? event-msg)
                      ;; Shouldn't be possible here, but we're being cautious:
                      (errorf "Bad event: %s" event) ; Log 'n drop
                      (event-msg-handler event-msg))
                    nil
                    (catch #+clj Throwable #+cljs :default t
                      (errorf #+clj t
                        "Chsk router handling error: %s" event))))))
            (catch #+clj Throwable #+cljs :default t
              (errorf #+clj t
                "Chsk router channel error!"))))
        (recur)))
    (fn stop! [] (async/close! ch-ctrl))))

;;;; Deprecated

#+clj
(defn start-chsk-router-loop!
  "DEPRECATED: Please use `start-chsk-router!` instead."
  [event-msg-handler ch-recv]
  (start-chsk-router! ch-recv
    ;; Old handler form: (fn [ev-msg ch-recv])
    (fn [ev-msg] (event-msg-handler ev-msg (:ch-recv ev-msg)))))

#+cljs
(defn start-chsk-router-loop!
  "DEPRECATED: Please use `start-chsk-router!` instead."
  [event-handler ch-recv]
  (start-chsk-router! ch-recv
    ;; Old handler form: (fn [ev ch-recv])
    (fn [ev-msg] (event-handler (:event ev-msg) (:ch-recv ev-msg)))))
