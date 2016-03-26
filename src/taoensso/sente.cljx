(ns taoensso.sente
  "Channel sockets. Otherwise known as The Shiz.

      Protocol  | client>server | client>server ?+ ack/reply | server>user push
    * WebSockets:       ✓              [1]                           ✓
    * Ajax:            [2]              ✓                           [3]

    [1] Emulate with cb-uuid wrapping
    [2] Emulate with dummy-cb wrapping
    [3] Emulate with long-polling

  Abbreviations:
    * chsk      - Channel socket (Sente's own pseudo \"socket\")
    * server-ch - Underlying web server's async channel that implement Sente's
                  server channel interface
    * sch       - server-ch alias
    * uid       - User-id. An application-level user identifier used for async
                  push. May have semantic meaning (e.g. username, email address),
                  may not (e.g. client/random id) - app's discretion.
    * cb        - Callback
    * tout      - Timeout
    * ws        - WebSocket/s
    * pstr      - Packed string. Arbitrary Clojure data serialized as a string
                  (e.g. edn) for client<->server comms.

  Special messages:
    * Callback wrapping: [<clj> <?cb-uuid>] for [1],[2]
    * Callback replies: :chsk/closed, :chsk/timeout, :chsk/error
    * Client-side events:
        [:chsk/handshake [<?uid> <?csrf-token> <?handshake-data>]],
        [:chsk/state <new-state>],
        [:chsk/recv <[buffered-evs]>] ; server>user push

    * Server-side events:
        [:chsk/ws-ping],
        [:chsk/bad-package <packed-str>],
        [:chsk/bad-event   <chsk-event>],
        [:chsk/uidport-open],
        [:chsk/uidport-close].

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

  {:author "Peter Taoussanis (@ptaoussanis)"}

  #+clj
  (:require
   [clojure.string     :as str]
   [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
   [taoensso.encore    :as enc    :refer (swap-in! reset-in! swapped have have! have?)]
   [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
   [taoensso.sente.interfaces :as interfaces])

  #+cljs
  (:require
   [clojure.string  :as str]
   [cljs.core.async :as async  :refer (<! >! put! chan)]
   [taoensso.encore :as enc    :refer (format swap-in! reset-in! swapped)
                               :refer-macros (have have! have?)]
   [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
   [taoensso.sente.interfaces :as interfaces])

  #+cljs
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(if (vector? taoensso.encore/encore-version)
  (enc/assert-min-encore-version [2 11 0])
  (enc/assert-min-encore-version  2.11))

;; (timbre/set-level! :trace) ; For debugging

;;;; Events
;; Clients & server both send `event`s and receive (i.e. route) `event-msg`s:
;;   - `event`s have the same form client+server side,
;;   - `event-msg`s have a similar but not identical form

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

(defn client-event-msg? [x]
  (and
    (map? x)
    (enc/keys= x #{:ch-recv :send-fn :state :event :id :?data})
    (let [{:keys [ch-recv send-fn state event]} x]
      (and
        (enc/chan? ch-recv)
        (ifn?      send-fn)
        (enc/atom? state)
        (event?    event)))))

(defn server-event-msg? [x]
  (and
    (map? x)
    (enc/keys= x #{:ch-recv :send-fn :connected-uids
                   :ring-req :client-id
                   :event :id :?data :?reply-fn :uid})
    (let [{:keys [ch-recv send-fn connected-uids
                  ring-req client-id event ?reply-fn]} x]
      (and
        (enc/chan?       ch-recv)
        (ifn?            send-fn)
        (enc/atom?       connected-uids)
        ;;
        (map?            ring-req)
        (enc/nblank-str? client-id)
        (event?          event)
        (or (nil? ?reply-fn)
            (ifn? ?reply-fn))))))

(defn- put-server-event-msg>ch-recv!
  "All server `event-msg`s go through this"
  [ch-recv {:as ev-msg :keys [event ?reply-fn]}]
  (let [[ev-id ev-?data :as valid-event] (as-event event)
        ev-msg* (merge ev-msg {:event     valid-event
                               :?reply-fn ?reply-fn
                               :id        ev-id
                               :?data     ev-?data})]
    (if-not (server-event-msg? ev-msg*)
      (warnf "Bad ev-msg: %s" ev-msg) ; Log 'n drop
      (put! ch-recv ev-msg*))))

;;; Note that cb replys need _not_ be `event` form!
#+cljs (defn cb-error?   [cb-reply-clj] (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply-clj))
#+cljs (defn cb-success? [cb-reply-clj] (not (cb-error? cb-reply-clj)))

;;;; Packing
;; * Client<->server payloads are arbitrary Clojure vals (cb replies or events).
;; * Payloads are packed for client<->server transit.
;; * Packing includes ->str encoding, and may incl. wrapping to carry cb info.

(defn- unpack* "pstr->clj" [packer pstr]
  (try
    (interfaces/unpack packer (have string? pstr))
    (catch #+clj Throwable #+cljs :default t
      (debugf "Bad package: %s (%s)" pstr t)
      [:chsk/bad-package pstr])))

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
  (have? string? prefixed-pstr)
  (let [prefix   (enc/substr prefixed-pstr 0 1)
        pstr     (enc/substr prefixed-pstr 1)
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

(declare
  ^:private send-buffered-server-evs>ws-clients!
  ^:private send-buffered-server-evs>ajax-clients!)

(defn make-channel-socket-server!
  "Takes a web server adapter[1] and returns a map with keys:
    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
    :send-fn ; (fn [user-id ev] for server>user push.
    :ajax-post-fn                ; (fn [ring-req]) for Ring CSRF-POST + chsk URL.
    :ajax-get-or-ws-handshake-fn ; (fn [ring-req]) for Ring GET + chsk URL.
    :connected-uids ; Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}}).

  Common options:
    :user-id-fn        ; (fn [ring-req]) -> unique user-id for server>user push.
    :csrf-token-fn     ; (fn [ring-req]) -> CSRF token for Ajax POSTs.
    :handshake-data-fn ; (fn [ring-req]) -> arb user data to append to handshake evs.
    :send-buf-ms-ajax  ; [2]
    :send-buf-ms-ws    ; [2]
    :packer            ; :edn (default), or an IPacker implementation (experimental).

  [1] e.g. `taoensso.sente.server-adapters.http-kit/http-kit-adapter` or
           `taoensso.sente.server-adapters.immutant/immutant-adapter`.
      You must have the necessary web-server dependency in your project.clj and
      the necessary entry in your namespace's `ns` form.

  [2] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."

  [web-server-adapter ; Actually a server-ch-adapter, but that may be confusing
   & [{:keys [recv-buf-or-n send-buf-ms-ajax send-buf-ms-ws
              user-id-fn csrf-token-fn handshake-data-fn packer]
       :or   {recv-buf-or-n (async/sliding-buffer 1000)
              send-buf-ms-ajax 100
              send-buf-ms-ws   30
              user-id-fn    (fn [ring-req] (get-in ring-req [:session :uid]))
              csrf-token-fn (fn [ring-req]
                              (or (get-in ring-req [:session :csrf-token])
                                  (get-in ring-req [:session :ring.middleware.anti-forgery/anti-forgery-token])
                                  (get-in ring-req [:session "__anti-forgery-token"])))
              handshake-data-fn (fn [ring-req] nil)
              packer :edn}}]]

  {:pre [(have? enc/pos-int? send-buf-ms-ajax send-buf-ms-ws)
         (have? #(satisfies? interfaces/IServerChanAdapter %)
           web-server-adapter)]}

  (let [packer  (interfaces/coerce-packer packer)
        ch-recv (chan recv-buf-or-n)
        conns_  (atom {:ws   {} ; {<uid> {<client-id> <server-ch>}}
                       :ajax {} ; {<uid> {<client-id> [<?server-ch> <udt-last-connected>]}}
                       })
        connected-uids_ (atom {:ws #{} :ajax #{} :any #{}})
        send-buffers_   (atom {:ws  {} :ajax  {}}) ; {<uid> [<buffered-evs> <#{ev-uuids}>]}

        user-id-fn
        (fn [ring-req client-id]
          ;; Allow uid to depend (in part or whole) on client-id. Be cautious
          ;; of security implications.
          (or (user-id-fn (assoc ring-req :client-id client-id)) ::nil-uid))

        connect-uid!
        (fn [type uid] {:pre [(have? uid)]}
          (let [newly-connected?
                (swap-in! connected-uids_ []
                  (fn [{:keys [ws ajax any] :as old-m}]
                    (let [new-m
                          (case type
                            :ws   {:ws (conj ws uid) :ajax ajax            :any (conj any uid)}
                            :ajax {:ws ws            :ajax (conj ajax uid) :any (conj any uid)})]
                      (swapped new-m
                        (let [old-any (:any old-m)
                              new-any (:any new-m)]
                          (when (and (not (contains? old-any uid))
                                          (contains? new-any uid))
                            :newly-connected))))))]
            newly-connected?))

        upd-connected-uid! ; Useful for atomic disconnects
        (fn [uid] {:pre [(have? uid)]}
          (let [newly-disconnected?
                (swap-in! connected-uids_ []
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
                      (swapped new-m
                        (let [old-any (:any old-m)
                              new-any (:any new-m)]
                          (when (and      (contains? old-any uid)
                                     (not (contains? new-any uid)))
                            :newly-disconnected))))))]
            newly-disconnected?))

        send-fn ; server>user (by uid) push
        (fn [user-id ev & [{:as opts :keys [flush?]}]]
          (let [uid     (if (= user-id :sente/all-users-without-uid) ::nil-uid user-id)
                _       (tracef "Chsk send: (->uid %s) %s" uid ev)
                _       (assert uid
                          (str "Support for sending to `nil` user-ids has been REMOVED. "
                               "Please send to `:sente/all-users-without-uid` instead."))
                _       (assert-event ev)
                ev-uuid (enc/uuid-str)

                flush-buffer!
                (fn [type]
                  (when-let
                      [pulled
                       (swap-in! send-buffers_ [type]
                         (fn [m]
                           ;; Don't actually flush unless the event buffered
                           ;; with _this_ send call is still buffered (awaiting
                           ;; flush). This means that we'll have many (go
                           ;; block) buffer flush calls that'll noop. They're
                           ;;  cheap, and this approach is preferable to
                           ;; alternatives like flush workers.
                           (let [[_ ev-uuids] (get m uid)]
                             (if (contains? ev-uuids ev-uuid)
                               (swapped (dissoc m uid)
                                        (get    m uid))
                               (swapped m nil)))))]
                    (let [[buffered-evs ev-uuids] pulled]
                      (have? vector? buffered-evs)
                      (have? set?    ev-uuids)

                      (let [packer-metas         (mapv meta buffered-evs)
                            combined-packer-meta (reduce merge {} packer-metas)
                            buffered-evs-ppstr   (pack packer
                                                   combined-packer-meta
                                                   buffered-evs)]
                        (tracef "buffered-evs-ppstr: %s (with meta %s)"
                          buffered-evs-ppstr combined-packer-meta)
                        (case type
                          :ws   (send-buffered-server-evs>ws-clients!   conns_
                                  uid buffered-evs-ppstr)
                          :ajax (send-buffered-server-evs>ajax-clients! conns_
                                  uid buffered-evs-ppstr))))))]

            (if (= ev [:chsk/close]) ; Currently undocumented
              (do
                (debugf "Chsk closing (client may reconnect): %s" uid)
                (when flush?
                  (doseq [type [:ws :ajax]]
                    (flush-buffer! type)))

                (doseq [server-ch (vals (get-in @conns_ [:ws uid]))]
                  (interfaces/sch-close! server-ch))

                (doseq [[?server-ch _] (vals (get-in @conns_ [:ajax uid]))]
                  (when-let [server-ch ?server-ch]
                    (interfaces/sch-close! server-ch))))

              (do
                ;; Buffer event
                (doseq [type [:ws :ajax]]
                  (swap-in! send-buffers_ [type uid]
                    (fn [?v]
                      (if-not ?v
                        [[ev] #{ev-uuid}]
                        (let [[buffered-evs ev-uuids] ?v]
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
       (interfaces/ring-req->server-ch-resp web-server-adapter ring-req
         {:on-open
          (fn [server-ch]
            (let [params        (get ring-req :params)
                  ppstr         (get params   :ppstr)
                  client-id     (get params   :client-id)
                  [clj has-cb?] (unpack packer ppstr)]

              (put-server-event-msg>ch-recv! ch-recv
                (merge ev-msg-const
                  {;; Note that the client-id is provided here just for the
                   ;; user's convenience. non-lp-POSTs don't actually need a
                   ;; client-id for Sente's own implementation:
                   :client-id client-id #_"unnecessary-for-non-lp-POSTs"

                   :ring-req  ring-req
                   :event     clj
                   :uid       (user-id-fn ring-req client-id)
                   :?reply-fn
                   (when has-cb?
                     (fn reply-fn [resp-clj] ; Any clj form
                       (tracef "Chsk send (ajax reply): %s" resp-clj)
                       ;; true iff apparent success:
                       (interfaces/sch-send! server-ch
                         (pack packer (meta resp-clj) resp-clj)
                         :close-after-send)))}))

              (when-not has-cb?
                (tracef "Chsk send (ajax reply): dummy-cb-200")
                (interfaces/sch-send! server-ch
                  (pack packer nil :chsk/dummy-cb-200)
                  :close-after-send))))}))

     :ajax-get-or-ws-handshake-fn ; Ajax handshake/poll, or WebSocket handshake
     (fn [ring-req]
       (let [csrf-token (csrf-token-fn ring-req)
             params     (get ring-req :params)
             client-id  (get params   :client-id)
             uid        (user-id-fn ring-req client-id)
             websocket? (:websocket? ring-req)

             receive-event-msg! ; Partial
             (fn [event & [?reply-fn]]
               (put-server-event-msg>ch-recv! ch-recv
                 (merge ev-msg-const
                   {:client-id client-id
                    :ring-req  ring-req
                    :event     event
                    :?reply-fn ?reply-fn
                    :uid       uid})))

             handshake!
             (fn [server-ch]
               (tracef "Handshake!")
               (let [?handshake-data (handshake-data-fn ring-req)
                     handshake-ev
                     (if-not (nil? ?handshake-data) ; Micro optimization
                       [:chsk/handshake [uid csrf-token ?handshake-data]]
                       [:chsk/handshake [uid csrf-token]])]
                 (interfaces/sch-send! server-ch
                   (pack packer nil handshake-ev)
                   (not websocket?))))]

         (if (str/blank? client-id)
           (let [err-msg "Client's Ring request doesn't have a client id. Does your server have the necessary keyword Ring middleware (`wrap-params` & `wrap-keyword-params`)?"]
             (errorf (str err-msg ": %s") ring-req)
             (throw (ex-info err-msg {:ring-req ring-req})))

           (interfaces/ring-req->server-ch-resp web-server-adapter ring-req
             {:on-open
              (fn [server-ch]
                (if websocket?
                  (do ; WebSocket handshake
                    (tracef "New WebSocket channel: %s (%s)"
                      uid (str server-ch)) ; _Must_ call `str` on server-ch
                    (reset-in! conns_ [:ws uid client-id] server-ch)
                    (when (connect-uid! :ws uid)
                      (receive-event-msg! [:chsk/uidport-open]))
                    (handshake! server-ch))

                  ;; Ajax handshake/poll connection:
                  (let [initial-conn-from-client?
                        (swap-in! conns_ [:ajax uid client-id]
                          (fn [?v] (swapped [server-ch (enc/now-udt)] (nil? ?v))))

                        handshake? (or initial-conn-from-client?
                                       (:handshake? params))]

                    (when (connect-uid! :ajax uid)
                      (receive-event-msg! [:chsk/uidport-open]))

                    ;; Client will immediately repoll:
                    (when handshake? (handshake! server-ch)))))

              :on-msg ; Only for WebSockets
              (fn [server-ch req-ppstr]
                (let [[clj ?cb-uuid] (unpack packer req-ppstr)]
                  (receive-event-msg! clj ; Should be ev
                    (when ?cb-uuid
                      (fn reply-fn [resp-clj] ; Any clj form
                        (tracef "Chsk send (ws reply): %s" resp-clj)
                        ;; true iff apparent success:
                        (interfaces/sch-send! server-ch
                          (pack packer (meta resp-clj) resp-clj ?cb-uuid)))))))

              :on-close ; We rely on `on-close` to trigger for _every_ conn!
              (fn [server-ch status]
                ;; `status` is currently unused; its form varies depending on
                ;; the underlying web server

                (if websocket?
                  (do ; WebSocket close
                    (swap-in! conns_ [:ws uid]
                      (fn [?m]
                        (let [new-m (dissoc ?m client-id)]
                          (if (empty? new-m) :swap/dissoc new-m))))

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
                        (receive-event-msg! [:chsk/uidport-close]))))

                  (do ; Ajax close
                    (swap-in! conns_ [uid :ajax client-id]
                      (fn [[server-ch udt-last-connected]] [nil udt-last-connected]))

                    (let [udt-disconnected (enc/now-udt)]
                      (go
                        ;; Allow some time for possible poller reconnects:
                        (<! (async/timeout 5000))
                        (let [disconnected?
                              (swap-in! conns_ [:ajax uid]
                                (fn [?m]
                                  (let [[_ ?udt-last-connected] (get ?m client-id)
                                        disconnected?
                                        (and ?udt-last-connected ; Not yet gc'd
                                          (>= udt-disconnected
                                              ?udt-last-connected))]
                                    (if-not disconnected?
                                      (swapped ?m (not :disconnected))
                                      (let [new-m (dissoc ?m client-id)]
                                        (swapped
                                          (if (empty? new-m) :swap/dissoc new-m)
                                          :disconnected))))))]
                          (when disconnected?
                            (when (upd-connected-uid! uid)
                              (receive-event-msg! [:chsk/uidport-close])))))))))}))))}))

(defn- send-buffered-server-evs>ws-clients!
  "Actually pushes buffered events (as packed-str) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-pstr]
  (tracef "send-buffered-server-evs>ws-clients!: %s" buffered-evs-pstr)
  (doseq [server-ch (vals (get-in @conns_ [:ws uid]))]
    (interfaces/sch-send! server-ch buffered-evs-pstr)))

(defn- send-buffered-server-evs>ajax-clients!
  "Actually pushes buffered events (as packed-str) to all uid's Ajax conns.
  Allows some time for possible Ajax poller reconnects."
  [conns_ uid buffered-evs-pstr & [{:keys [nmax-attempts ms-base ms-rand]
                                    ;; <= 7 attempts at ~135ms ea = 945ms
                                    :or   {nmax-attempts 7
                                           ms-base       90
                                           ms-rand       90}}]]
  (comment (* 7 (+ 90 (/ 90 2.0))))
  (tracef "send-buffered-server-evs>ajax-clients!: %s" buffered-evs-pstr)
  (let [;; All connected/possibly-reconnecting client uuids:
        client-ids-unsatisfied (keys (get-in @conns_ [:ajax uid]))]
    (when-not (empty? client-ids-unsatisfied)
      ;; (tracef "client-ids-unsatisfied: %s" client-ids-unsatisfied)
      (go-loop [n 0 client-ids-satisfied #{}]
        (let [?pulled ; nil or {<client-id> [<?server-ch> <udt-last-connected>]}
              (swap-in! conns_ [:ajax uid]
                (fn [m] ; {<client-id> [<?server-ch> <udt-last-connected>]}
                  (let [ks-to-pull (remove client-ids-satisfied (keys m))]
                    ;; (tracef "ks-to-pull: %s" ks-to-pull)
                    (if (empty? ks-to-pull)
                      (swapped m nil)
                      (swapped
                        (reduce
                          (fn [m k]
                            (let [[?server-ch udt-last-connected] (get m k)]
                              (assoc m k [nil udt-last-connected])))
                          m ks-to-pull)
                        (select-keys m ks-to-pull))))))]
          (have? [:or nil? map?] ?pulled)
          (let [?newly-satisfied
                (when ?pulled
                  (reduce-kv
                   (fn [s client-id [?server-ch _]]
                     (if (or (nil? ?server-ch)
                             ;; server-ch may have closed already (`send!` will noop):
                             (not (interfaces/sch-send! ?server-ch buffered-evs-pstr
                                    :close-after-send)))
                       s
                       (conj s client-id))) #{} ?pulled))
                now-satisfied (into client-ids-satisfied ?newly-satisfied)]
            ;; (tracef "now-satisfied: %s" now-satisfied)
            (when (and (< n nmax-attempts)
                       (some (complement now-satisfied) client-ids-unsatisfied))
              ;; Allow some time for possible poller reconnects:
              (<! (async/timeout (+ ms-base (rand-int ms-rand))))
              (recur (inc n) now-satisfied))))))))

;;;; Client API

#+cljs (def ajax-lite "Alias of `taoensso.encore/ajax-lite`" enc/ajax-lite)
#+cljs
(defprotocol IChSocket
  (-chsk-connect!   [chsk]         "Implementation detail")
  (-chsk-send!      [chsk ev opts] "Implementation detail")
  (chsk-destroy!    [chsk] "DEPRECATED: Please use `chsk-disconnect!` instead")
  (chsk-disconnect! [chsk] "Disconnects channel socket")
  (chsk-reconnect!  [chsk] "Reconnects channel socket (useful for reauthenticating after login/logout, etc.)"))

#+cljs
(defn chsk-send!
  "Sends `[ev-id ev-?data :as event]`, returns true on apparent success."
  ([chsk ev]                 (chsk-send! chsk ev {}))
  ([chsk ev ?timeout-ms ?cb] (chsk-send! chsk ev {:timeout-ms ?timeout-ms
                                                  :cb         ?cb}))
  ([chsk ev opts]
     (tracef "Chsk send: (%s) %s" (assoc opts :cb (boolean (:cb opts))) ev)
     (-chsk-send! chsk ev opts)))

#+cljs
(defn- assert-send-args [x ?timeout-ms ?cb]
  (assert-event x)
  (assert (or (and (nil? ?timeout-ms) (nil? ?cb))
              (and (enc/nneg-int? ?timeout-ms)))
          (format "cb requires a timeout; timeout-ms should be a +ive integer: %s"
           ?timeout-ms))
  (assert (or (nil? ?cb) (ifn? ?cb) (enc/chan? ?cb))
          (format "cb should be nil, an ifn, or a channel: %s" (type ?cb))))

#+cljs
(defn- pull-unused-cb-fn! [cbs-waiting_ ?cb-uuid]
  (when-let [cb-uuid ?cb-uuid]
    (swap-in! cbs-waiting_ [cb-uuid]
      (fn [?f] (swapped :swap/dissoc ?f)))))

#+cljs
(defn- merge>chsk-state! [{:keys [chs state_] :as chsk} merge-state]
  (let [[old-state new-state]
        (swap-in! state_ []
          (fn [old-state]
            (let [new-state (merge old-state merge-state)
                  ;; Is this a reasonable way of helping client distinguish
                  ;; cause of an auto reconnect? Didn't give it much thought...
                  new-state (if-not (and (:requested-reconnect-pending? old-state)
                                              (:open? new-state)
                                         (not (:open? old-state)))
                              new-state
                              (-> new-state
                                  (dissoc :requested-reconnect-pending?)
                                  (assoc  :requested-reconnect? true)))]
              (swapped new-state [old-state new-state]))))]
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
    (do (have? enc/chan? ?cb)
        (assert-event ev)
        (let [[ev-id _] ev
              cb-ch ?cb]
          (fn [reply]
            (put! cb-ch [(keyword (str (enc/fq-name ev-id) ".cb"))
                         reply]))))))

#+cljs
(defn- receive-buffered-evs! [chs clj]
  (tracef "receive-buffered-evs!: %s" clj)
  (let [buffered-evs (have vector? clj)]
    (doseq [ev buffered-evs]
      (assert-event ev)
      (put! (:<server chs) ev))))

#+cljs
(defn- handle-when-handshake! [chsk chs clj]
  (let [handshake? (and (vector? clj) ; Nb clj may be callback reply
                        (= (first clj) :chsk/handshake))]
    (tracef "handle-when-handshake (%s): %s"
      (if handshake? :handshake :non-handshake) clj)

    (when handshake?
      (let [[_ [?uid ?csrf-token ?handshake-data] :as handshake-ev] clj]
        (when (str/blank? ?csrf-token)
          (warnf "SECURITY WARNING: no CSRF token available for use by Sente"))

        (merge>chsk-state! chsk
          {:open?      true
           :uid        ?uid
           :csrf-token ?csrf-token

           ;; Could also just merge ?handshake-data into chsk state here, but
           ;; it seems preferable (?) to instead register a unique
           ;; :chsk/handshake event
           })

        (assert-event handshake-ev)
        (put! (:internal chs) handshake-ev)

        :handled))))

#+cljs
(defrecord ChWebSocket ; Handles (re)connections, keep-alives, cbs, etc.
    [client-id chs params packer url
     state_ ; {:type _ :open? _ :uid _ :csrf-token _}
     cbs-waiting_ ; {<cb-uuid> <fn> ...}
     socket_ kalive-ms kalive-timer_ kalive-due?_
     onerror-fn
     backoff-ms-fn ; (fn [nattempt]) -> msecs
     active-retry-id_ retry-count_]

  IChSocket
  (-chsk-send! [chsk ev {:as opts ?timeout-ms :timeout-ms ?cb :cb :keys [flush?]}]
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (do (warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))

        ;; TODO Buffer before sending (but honor `:flush?`)
        (let [?cb-uuid (when ?cb-fn (enc/uuid-str 6))
              ppstr    (pack packer (meta ev) ev ?cb-uuid)]

          (when-let [cb-uuid ?cb-uuid]
            (reset-in! cbs-waiting_ [cb-uuid] (have ?cb-fn))
            (when-let [timeout-ms ?timeout-ms]
              (go (<! (async/timeout timeout-ms))
                  (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
                    (cb-fn* :chsk/timeout)))))

          (try
            (.send @socket_ ppstr)
            (reset! kalive-due?_ false)
            :apparent-success
            (catch js/Error e
              (errorf e "Chsk send error")
              (when-let [cb-uuid ?cb-uuid]
                (let [cb-fn* (or (pull-unused-cb-fn! cbs-waiting_ cb-uuid)
                                 (have ?cb-fn))]
                  (cb-fn* :chsk/error)))
              false))))))

  (chsk-destroy!    [chsk] (chsk-disconnect! chsk))
  (chsk-disconnect! [chsk]
    (reset! active-retry-id_ "disconnected")
    (merge>chsk-state! chsk {:open? false})
    (when-let [s @socket_] (.close s 1000 "CLOSE_NORMAL")))

  (chsk-reconnect! [chsk]
    (reset! active-retry-id_ "reconnecting")
    (merge>chsk-state! chsk {:open? false :requested-reconnect-pending? true})
    (when-let [s @socket_] (.close s 3000 "SENTE_RECONNECT"))
    (-chsk-connect! chsk))

  (-chsk-connect! [chsk]
    (when-let [WebSocket (or (enc/oget js/window "WebSocket")
                             (enc/oget js/window "MozWebSocket"))]
      (let [retry-id (enc/uuid-str)
            connect-fn
            (fn connect-fn []
              (let [retry-fn
                    (fn []
                      (when (= @active-retry-id_ retry-id)
                        (let [retry-count* (swap! retry-count_ inc)
                              backoff-ms   (backoff-ms-fn retry-count*)]
                          (.clearInterval js/window @kalive-timer_)
                          (warnf "Chsk is closed: will try reconnect (%s)"
                            retry-count*)
                          (.setTimeout js/window connect-fn backoff-ms))))

                    ?socket
                    (try
                      (WebSocket.
                        (enc/merge-url-with-query-string url
                          (merge params ; 1st (don't clobber impl.):
                            {:client-id client-id})))
                      (catch js/Error e
                        (errorf e "WebSocket js/Error")
                        nil))]

                (if-not ?socket
                  (retry-fn) ; Couldn't even get a socket

                  (reset! socket_
                    (doto ?socket
                      (aset "onerror" onerror-fn)

                      (aset "onmessage" ; Nb receives both push & cb evs!
                        (fn [ws-ev]
                          (let [;; Nb may or may NOT satisfy `event?` since we also
                                ;; receive cb replies here! This is actually why
                                ;; we prefix our pstrs to indicate whether they're
                                ;; wrapped or not.
                                ppstr (enc/oget ws-ev "data")
                                [clj ?cb-uuid] (unpack packer ppstr)]
                            ;; (assert-event clj) ;; NO!
                            (or
                              (and (handle-when-handshake! chsk chs clj)
                                   (reset! retry-count_ 0))
                              (if-let [cb-uuid ?cb-uuid]
                                (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting_
                                                 cb-uuid)]
                                  (cb-fn clj)
                                  (warnf "Cb reply w/o local cb-fn: %s" clj))
                                (let [buffered-evs clj]
                                  (receive-buffered-evs! chs buffered-evs)))))))

                      (aset "onopen"
                        (fn [_ws-ev]
                          (reset! kalive-timer_
                            (.setInterval js/window
                              (fn []
                                (when @kalive-due?_ ; Don't ping unnecessarily
                                  (chsk-send! chsk [:chsk/ws-ping]))
                                (reset! kalive-due?_ true))
                              kalive-ms))
                          ;; NO, better for server to send a handshake!:
                          ;; (merge>chsk-state! chsk {:open? true})
                          ))

                      ;; Fires repeatedly (on each connection attempt) while
                      ;; server is down:
                      (aset "onclose"
                        (fn [_ws-ev]
                          (merge>chsk-state! chsk {:open? false})
                          (retry-fn))))))))]

        (reset! active-retry-id_ retry-id)
        (reset! retry-count_ 0)
        (connect-fn)
        chsk))))

#+cljs
(defrecord ChAjaxSocket ; Handles (re)polling, etc.
    [client-id chs params packer url state_
     timeout-ms ajax-opts curr-xhr_
     active-retry-id_
     backoff-ms-fn]

  IChSocket
  (-chsk-send! [chsk ev {:as opts ?timeout-ms :timeout-ms ?cb :cb :keys [flush?]}]
    (assert-send-args ev ?timeout-ms ?cb)
    (let [?cb-fn (cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (do (warnf "Chsk send against closed chsk.")
            (when ?cb-fn (?cb-fn :chsk/closed)))

        ;; TODO Buffer before sending (but honor `:flush?`)
        (let [csrf-token (:csrf-token @state_)]
          (ajax-lite url
            (merge ajax-opts
              {:method :post :timeout-ms ?timeout-ms
               :resp-type :text ; We'll do our own pstr decoding
               :headers
               (merge (:headers ajax-opts) ; 1st (don't clobber impl.):
                 {:X-CSRF-Token csrf-token})

               :params
               (let [ppstr (pack packer (meta ev) ev (when ?cb-fn :ajax-cb))]
                 (merge params ; 1st (don't clobber impl.):
                   {:_           (enc/now-udt) ; Force uncached resp

                    ;; A duplicate of X-CSRF-Token for user's convenience and
                    ;; for back compatibility with earlier CSRF docs:
                    :csrf-token  csrf-token

                    ;; Just for user's convenience here. non-lp-POSTs don't
                    ;; actually need a client-id for Sente's own implementation:
                    :client-id   client-id

                    :ppstr       ppstr}))})

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

  (chsk-destroy!    [chsk] (chsk-disconnect! chsk))
  (chsk-disconnect! [chsk]
    (reset! active-retry-id_ "disconnected")
    (merge>chsk-state! chsk {:open? false})
    (when-let [x @curr-xhr_] (.abort x)))

  (chsk-reconnect! [chsk]
    (reset! active-retry-id_ "reconnecting")
    (merge>chsk-state! chsk {:open? false :requested-reconnect-pending? true})
    (when-let [x @curr-xhr_] (.abort x))
    (-chsk-connect! chsk))

  (-chsk-connect! [chsk]
    (let [retry-id (enc/uuid-str)
          poll-fn ; async-poll-for-update-fn
          (fn poll-fn [retry-count]
            (tracef "async-poll-for-update!")
            (let [retry-fn
                  (fn []
                    (when (= @active-retry-id_ retry-id)
                      (let [retry-count* (inc retry-count)
                            backoff-ms   (backoff-ms-fn retry-count*)]
                        (warnf "Chsk is closed: will try reconnect (%s)"
                          retry-count*)
                        (.setTimeout js/window
                          (fn [] (poll-fn retry-count*))
                          backoff-ms))))]

              (reset! curr-xhr_
                (ajax-lite url
                  (merge ajax-opts
                    {:method :get :timeout-ms timeout-ms
                     :resp-type :text ; Prefer to do our own pstr reading
                     :params
                     (merge

                       ;; Note that user params here are actually POST params for
                       ;; convenience. Contrast: WebSocket params sent as query
                       ;; params since there's no other choice there.
                       params ; 1st (don't clobber impl.):

                       {:_          (enc/now-udt) ; Force uncached resp
                        :client-id  client-id}

                       ;; A truthy :handshake? param will prompt server to
                       ;; reply immediately with a handshake response,
                       ;; letting us confirm that our client<->server comms
                       ;; are working:
                       (when-not (:open? @state_) {:handshake? true}))})

                  (fn ajax-cb [{:keys [?error ?content]}]
                    (if ?error
                      (cond
                        (= ?error :timeout) (poll-fn 0)
                        ;; (= ?error :abort) ; Abort => intentional, not an error
                        :else
                        (do (merge>chsk-state! chsk {:open? false})
                            (retry-fn)))

                      ;; The Ajax long-poller is used only for events, never cbs:
                      (let [content ?content
                            ppstr   content
                            [clj _] (unpack packer ppstr)]
                        (or
                          (handle-when-handshake! chsk chs clj)
                          ;; Actually poll for an application reply:
                          (let [buffered-evs clj]
                            (receive-buffered-evs! chs buffered-evs)
                            (merge>chsk-state! chsk {:open? true})))

                        (poll-fn 0))))))))]

      (reset! active-retry-id_ retry-id)
      (poll-fn 0)
      chsk)))

#+cljs
(defn- get-chsk-url [protocol chsk-host chsk-path type]
  (let [protocol (case type :ajax protocol
                            :ws   (if (= protocol "https:") "wss:" "ws:"))]
    (str protocol "//" (enc/path chsk-host chsk-path))))

#+cljs
(defn make-channel-socket-client!
  "Returns a map with keys:
    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
             ; May `put!` (inject) arbitrary `event`s to this channel.
    :send-fn ; (fn [event & [?timeout-ms ?cb-fn]]) for client>server send.
    :state   ; Watchable, read-only (atom {:type _ :open? _ :uid _ :csrf-token _}).
    :chsk    ; IChSocket implementer. You can usu. ignore this.

  Common options:
    :type           ; e/o #{:auto :ws :ajax}. You'll usually want the default (:auto)
    :host           ; Server host (defaults to current page's host)
    :params         ; Map of any params to incl. in chsk Ring requests (handy for
                    ; application-level auth, etc.)
    :ws-kalive-ms   ; Ping to keep a WebSocket conn alive if no activity w/in given
                    ; number of milliseconds
    :lp-timeout-ms  ; Ping to keep a long-polling (Ajax) conn alive '' [1]
    :packer         ; :edn (default), or an IPacker implementation (experimental)
    :ajax-opts      ; Base opts map provided to `taoensso.encore/ajax-lite`
    :wrap-recv-evs? ; Should events from server be wrapped in [:chsk/recv _]?

  [1] If you're using Immutant and override the default :lp-timeout-ms, you'll
      need to provide the same timeout value to
      `taoensso.sente.server-adapters.immutant/make-immutant-adapter` and use
      the result of that function as the web server adapter to your server-side
      `make-channel-socket-server!`."
  [path &
   [{:keys [type host params recv-buf-or-n ws-kalive-ms lp-timeout-ms packer
            client-id ajax-opts wrap-recv-evs? backoff-ms-fn]
     :as   opts
     :or   {type          :auto
            recv-buf-or-n (async/sliding-buffer 2048) ; Mostly for buffered-evs
            ws-kalive-ms  25000 ; < Heroku 30s conn timeout
            lp-timeout-ms 25000 ; ''
            packer        :edn
            onerror-fn    (fn [ws-ev] (errorf "WebSocket error: %s" ws-ev)) ; Only applies for type :ws
            client-id     (or (:client-uuid opts) ; Backwards compatibility
                                (enc/uuid-str))

            ;; TODO Deprecated. Default to false later, then eventually just
            ;; drop this option altogether? - here now for back compatibility:
            wrap-recv-evs? true

            backoff-ms-fn  enc/exp-backoff}}
    _deprecated-more-opts]]

  {:pre [(have? [:in #{:ajax :ws :auto}] type)
         (have? enc/nblank-str?          client-id)]}

  (when (not (nil? _deprecated-more-opts))
    (warnf "`make-channel-socket-client!` fn signature CHANGED with Sente v0.10.0."))
  (when (contains? opts :lp-timeout)
    (warnf ":lp-timeout opt has CHANGED; please use :lp-timout-ms."))

  (let [packer (interfaces/coerce-packer packer)

        win-location (enc/get-window-location)
        win-protocol      (:protocol win-location)
        host     (or host (:host     win-location))
        path     (or path (:pathname win-location))

        private-chs {:state    (chan (async/sliding-buffer 10))
                     :internal (chan (async/sliding-buffer 10))
                     :<server  (chan recv-buf-or-n)}

        ever-opened?_ (atom false)
        state*        (fn [state]
                        (if (or (not (:open? state)) @ever-opened?_) state
                          (do (reset! ever-opened?_ true)
                              (assoc state :first-open? true))))

        ;; TODO map< is deprecated in favour of transducers (but needs Clojure 1.7+)

        public-ch-recv
        (async/merge
          [(:internal private-chs)
           (async/map< (fn [state] [:chsk/state (state* state)]) (:state private-chs))

           (let [<server-ch (:<server private-chs)]
             (if wrap-recv-evs?
               (async/map< (fn [ev] [:chsk/recv ev]) <server-ch)
               (async/map< (fn [ev]
                             (let [[id ?data] ev]
                               ;; Server shouldn't send :chsk/ events. As a
                               ;; matter of hygiene, ensure no :chsk/_ evs are
                               ;; received over <server-ch
                               (have? #(not= % "chsk") (namespace id))
                               ev))
                 <server-ch)))]
          ;; recv-buf-or-n ; Seems to be malfunctioning
          )

        chsk
        (or
         (and (not= type :ajax)
              (-chsk-connect!
                (map->ChWebSocket
                  {:client-id client-id
                   :chs       private-chs
                   :params    params
                   :packer    packer
                   :url (if-let [f (:chsk-url-fn opts)]
                          (f path win-location :ws) ; Deprecated
                          (get-chsk-url win-protocol host path :ws))

                   :state_ (atom {:type :ws :open? false})
                   :cbs-waiting_     (atom {})
                   :socket_          (atom nil)
                   :kalive-ms        ws-kalive-ms
                   :kalive-timer_    (atom nil)
                   :kalive-due?_     (atom true)
                   :onerror-fn       onerror-fn
                   :backoff-ms-fn    backoff-ms-fn
                   :active-retry-id_ (atom "pending")
                   :retry-count_     (atom 0)})))

         (and (not= type :ws)
              (-chsk-connect!
                (map->ChAjaxSocket
                  {:client-id client-id
                   :chs       private-chs
                   :params    params
                   :packer    packer
                   :url (if-let [f (:chsk-url-fn opts)]
                          (f path win-location :ajax) ; Deprecated
                          (get-chsk-url win-protocol host path :ajax))

                   :state_ (atom {:type :ajax :open? false})
                   :timeout-ms       lp-timeout-ms
                   :ajax-opts        ajax-opts
                   :curr-xhr_        (atom nil)
                   :backoff-ms-fn    backoff-ms-fn
                   :active-retry-id_ (atom "pending")}))))

        _ (assert chsk "Failed to create channel socket")
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

;;;; Event-msg routers (handler loops)

(defn- -start-chsk-router!
  [server? ch-recv event-msg-handler opts]
  (let [{:keys [trace-evs? error-handler]} opts
        ch-ctrl (chan)]

    (go-loop []
      (let [[v p] (async/alts! [ch-recv ch-ctrl])
            stop? (enc/kw-identical? p  ch-ctrl)]

        (when-not stop?
          (let [{:as event-msg :keys [event]} v
                [_ ?error]
                (enc/catch-errors
                  (when trace-evs? (tracef "Pre-handler event: %s" event))
                  (event-msg-handler
                    (if server?
                      (have! server-event-msg? event-msg)
                      (have! client-event-msg? event-msg))))]

            (when-let [e ?error]
              (let [[_ ?error2]
                    (enc/catch-errors
                      (if-let [eh error-handler]
                        (error-handler e event-msg)
                        (errorf e "Chsk router `event-msg-handler` error: %s" event)))]
                (when-let [e2 ?error2]
                  (errorf e2 "Chsk router `error-handler` error: %s" event))))

            (recur)))))

    (fn stop! [] (async/close! ch-ctrl))))

(defn start-server-chsk-router!
  "Creates a go-loop to call `(event-msg-handler <server-event-msg>)` and
  returns a `(fn stop! [])`. Catches & logs errors.

  Advanced users may instead prefer to write their own loop against `ch-recv`."
  [ch-recv event-msg-handler & [{:as opts :keys [trace-evs? error-handler]}]]
  (-start-chsk-router! :server ch-recv event-msg-handler opts))

(defn start-client-chsk-router!
  "Creates a go-loop to call `(event-msg-handler <client-event-msg>)` and
  returns a `(fn stop! [])`. Catches & logs errors.

  Advanced users may instead prefer to write their own loop against `ch-recv`."
  [ch-recv event-msg-handler & [{:as opts :keys [trace-evs? error-handler]}]]
  (-start-chsk-router! (not :server) ch-recv event-msg-handler opts))

;;;; Platform aliases

(def event-msg? #+clj server-event-msg? #+cljs client-event-msg?)

(def make-channel-socket!
  #+clj  make-channel-socket-server!
  #+cljs make-channel-socket-client!)

(def start-chsk-router!
  #+clj  start-server-chsk-router!
  #+cljs start-client-chsk-router!)

;;;; Deprecated

#+clj
(defn start-chsk-router-loop!
  "DEPRECATED: Please use `start-chsk-router!` instead."
  [event-msg-handler ch-recv]
  (start-server-chsk-router! ch-recv
    ;; Old handler form: (fn [ev-msg ch-recv])
    (fn [ev-msg] (event-msg-handler ev-msg (:ch-recv ev-msg)))))

#+cljs
(defn start-chsk-router-loop!
  "DEPRECATED: Please use `start-chsk-router!` instead."
  [event-handler ch-recv]
  (start-client-chsk-router! ch-recv
    ;; Old handler form: (fn [ev ch-recv])
    (fn [ev-msg] (event-handler (:event ev-msg) (:ch-recv ev-msg)))))

(defn set-logging-level!
  "DEPRECATED. Please use `timbre/set-level!` instead."
  [level] (timbre/set-level! level))

#+cljs
(def ajax-call "DEPRECATED: Please use `ajax-lite` instead."
  enc/ajax-lite)

#+cljs
(def default-chsk-url-fn "DEPRECATED"
  (fn [path {:as location :keys [adjusted-protocol host pathname]} websocket?]
    (str adjusted-protocol "//" host (or path pathname))))
