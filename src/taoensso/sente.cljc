(ns taoensso.sente
  "Channel sockets for Clojure/Script.

      Protocol  | client>server | client>server ?+ ack/reply | server>user push
    * WebSockets:       ✓              [1]                           ✓
    * Ajax:            [2]              ✓                           [3]

    [1] Emulate with cb-uuid wrapping
    [2] Emulate with dummy-cb wrapping
    [3] Emulate with long-polling

  Abbreviations:
    * chsk      - Channel socket (Sente's own pseudo \"socket\")
    * server-ch - Underlying web server's async channel that implement
                  Sente's server channel interface
    * sch       - server-ch alias
    * uid       - User-id. An application-level user identifier used for async
                  push. May have semantic meaning (e.g. username, email address),
                  may not (e.g. client/random id) - app's discretion.
    * cb        - Callback
    * tout      - Timeout
    * ws        - WebSocket/s
    * pstr      - Packed string. Arbitrary Clojure data serialized as a
                  string (e.g. edn) for client<->server comms
    * udt       - Unix timestamp (datetime long)

  Special messages:
    * Callback wrapping: [<clj> <?cb-uuid>] for [1], [2]
    * Callback replies: :chsk/closed, :chsk/timeout, :chsk/error

    * Client-side events:
        [:chsk/handshake [<?uid> nil[4] <?handshake-data> <first-handshake?>]]
        [:chsk/state [<old-state-map> <new-state-map>]]
        [:chsk/recv <ev-as-pushed-from-server>] ; Server>user push
        [:chsk/ws-ping]

    * Server-side events:
        [:chsk/bad-package <packed-str>]
        [:chsk/bad-event   <event>]
        [:chsk/uidport-open  <uid>]
        [:chsk/uidport-close <uid>]
        [:chsk/ws-ping]

  Channel socket state map:
    :type               - e/o #{:auto :ws :ajax}
    :open?              - Truthy iff chsk appears to be open (connected) now
    :ever-opened?       - Truthy iff chsk handshake has ever completed successfully
    :first-open?        - Truthy iff chsk just completed first successful handshake
    :uid                - User id provided by server on handshake,    or nil
    :handshake-data     - Arb user data provided by server on handshake
    :last-ws-error      - ?{:udt _ :ev <WebSocket-on-error-event>}
    :last-ws-close      - ?{:udt _ :ev <WebSocket-on-close-event>
                            :clean? _ :code _ :reason _}
    :last-close         - ?{:udt _ :reason _}, with reason e/o
                            #{nil :requested-disconnect :requested-reconnect
                             :downgrading-ws-to-ajax :unexpected}
    :udt-next-reconnect - Approximate udt of next scheduled auto-reconnect attempt

  Notable implementation details:
    * core.async is used liberally where brute-force core.async allows for
      significant implementation simplifications. We lean on core.async's
      efficiency here.
    * For WebSocket fallback we use long-polling rather than HTTP 1.1 streaming
      (chunked transfer encoding). Http-kit _does_ support chunked transfer
      encoding but a small minority of browsers &/or proxies do not. Instead of
      implementing all 3 modes (WebSockets, streaming, long-polling) - it seemed
      reasonable to focus on the two extremes (performance + compatibility).
      In any case client support for WebSockets is growing rapidly so fallback
      modes will become increasingly irrelevant while the extra simplicity will
      continue to pay dividends.

  General-use notes:
    * Single HTTP req+session persists over entire chsk session but cannot
      modify sessions! Use standard a/sync HTTP Ring req/resp for logins, etc.
    * Easy to wrap standard HTTP Ring resps for transport over chsks. Prefer
      this approach to modifying handlers (better portability).

  [4] Used to be a csrf-token. Was removed in v1.14 for security reasons.
  A `nil` remains for semi-backwards-compatibility with pre-v1.14 clients."

  {:author "Peter Taoussanis (@ptaoussanis)"}

  #?(:clj
     (:require
      [clojure.string :as str]
      [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
      [taoensso.encore :as enc :refer (swap-in! reset-in! swapped have have! have?)]
      [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]
      [taoensso.sente.interfaces :as interfaces]))

  #?(:cljs
     (:require
      [clojure.string :as str]
      [cljs.core.async :as async :refer (<! >! put! chan)]
      [taoensso.encore :as enc :refer (format swap-in! reset-in! swapped)
       :refer-macros (have have! have?)]
      [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
      [taoensso.sente.interfaces :as interfaces]))

  #?(:cljs
     (:require-macros
      [cljs.core.async.macros :as asyncm :refer (go go-loop)]
      [taoensso.sente :as sente-macros :refer (elide-require)]))

  #?(:clj
     (:import
      [org.java_websocket.client WebSocketClient])))

(if (vector? taoensso.encore/encore-version)
  (enc/assert-min-encore-version [2 105 0])
  (enc/assert-min-encore-version  2.105))

(def sente-version "Useful for identifying client/server mismatch"
  [1 11 0])

#?(:cljs (def ^:private node-target? (= *target* "nodejs")))

;; (timbre/set-level! :trace) ; Uncomment for debugging
(defonce debug-mode?_ (atom false))

;;;; Events
;; Clients & server both send `event`s and receive (i.e. route) `event-msg`s:
;;   - `event`s have the same form client+server side,
;;   - `event-msg`s have a similar but not identical form

(defn- expected [expected x] {:expected expected :actual {:type (type x) :value x}})
(defn validate-event
  "Returns nil if given argument is a valid [ev-id ?ev-data] form. Otherwise
  returns a map of validation errors like `{:wrong-type {:expected _ :actual _}}`."
  [x]
  (cond
    (not (vector? x))        {:wrong-type   (expected :vector x)}
    (not (#{1 2} (count x))) {:wrong-length (expected #{1 2}  x)}
    :else
    (let [[ev-id _] x]
      (cond
        (not (keyword? ev-id))  {:wrong-id-type   (expected :keyword            ev-id)}
        (not (namespace ev-id)) {:unnamespaced-id (expected :namespaced-keyword ev-id)}
        :else nil))))

(defn assert-event
  "Returns given argument if it is a valid [ev-id ?ev-data] form. Otherwise
  throws a validation exception."
  [x]
  (when-let [errs (validate-event x)]
    (throw (ex-info "Invalid event" {:given x :errors errs}))))

(defn event? "Valid [ev-id ?ev-data] form?" [x] (nil? (validate-event x)))
(defn as-event [x]
  (if-let [errs (validate-event x)]
    ;; [:chsk/bad-event {:given x :errors errs}] ; Breaking change
    [:chsk/bad-event x]
    x))

(defn client-event-msg? [x]
  (and
    (map? x)
    (enc/ks>= #{:ch-recv :send-fn :state :event :id :?data} x)
    (let [{:keys [ch-recv send-fn state event]} x]
      (and
        (enc/chan? ch-recv)
        (ifn?      send-fn)
        (enc/atom? state)
        (event?    event)))))

(defn server-event-msg? [x]
  (and
    (map? x)
    (enc/ks>= #{:ch-recv :send-fn :connected-uids :send-buffers
                :ring-req :client-id
                :event :id :?data :?reply-fn :uid} x)
    (let [{:keys [ch-recv send-fn connected-uids send-buffers
                  ring-req client-id event ?reply-fn]} x]
      (and
        (enc/chan?       ch-recv)
        (ifn?            send-fn)
        (enc/atom?       connected-uids)
        (enc/atom?       send-buffers)
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
#?(:cljs (defn cb-error? [cb-reply-clj] (#{:chsk/closed :chsk/timeout :chsk/error} cb-reply-clj)))
#?(:cljs (defn cb-success? [cb-reply-clj] (not (cb-error? cb-reply-clj))))

;;;; Packing
;; * Client<->server payloads are arbitrary Clojure vals (cb replies or events).
;; * Payloads are packed for client<->server transit.
;; * Packing includes ->str encoding, and may incl. wrapping to carry cb info.

(defn- unpack "prefixed-pstr->[clj ?cb-uuid]"
  [packer prefixed-pstr]
  (have? string? prefixed-pstr)
  (let [wrapped? (enc/str-starts-with? prefixed-pstr "+")
        pstr     (subs prefixed-pstr 1)
        clj
        (try
          (interfaces/unpack packer pstr)
          (catch #?(:clj Throwable :cljs :default) t
            (debugf "Bad package: %s (%s)" pstr t)
            [:chsk/bad-package pstr]))

        [clj ?cb-uuid] (if wrapped? clj [clj nil])
        ?cb-uuid (if (= 0 ?cb-uuid) :ajax-cb ?cb-uuid)]

    (tracef "Unpacking: %s -> %s" prefixed-pstr [clj ?cb-uuid])
    [clj ?cb-uuid]))

(defn- pack "clj->prefixed-pstr"
  ([packer clj]
   (let [;; "-" prefix => Unwrapped (has no callback)
         pstr (str "-" (interfaces/pack packer clj))]
     (tracef "Packing (unwrapped): %s -> %s" clj pstr)
     pstr))

  ([packer clj ?cb-uuid]
   (let [;;; Keep wrapping as light as possible:
         ?cb-uuid    (if (= ?cb-uuid :ajax-cb) 0 ?cb-uuid)
         wrapped-clj (if ?cb-uuid [clj ?cb-uuid] [clj])
         ;; "+" prefix => Wrapped (has callback)
         pstr (str "+" (interfaces/pack packer wrapped-clj))]
     (tracef "Packing (wrapped): %s -> %s" wrapped-clj pstr)
     pstr)))

(deftype EdnPacker []
  interfaces/IPacker
  (pack   [_ x] (enc/pr-edn   x))
  (unpack [_ s] (enc/read-edn s)))

(def ^:private default-edn-packer (EdnPacker.))

(defn- coerce-packer [x]
  (if (= x :edn)
    default-edn-packer
    (have #(satisfies? interfaces/IPacker %) x)))

(comment
  (do
    (require '[taoensso.sente.packers.transit :as transit])
    (def ^:private default-transit-json-packer (transit/get-transit-packer)))

  (let [pack   interfaces/pack
        unpack interfaces/unpack
        data   {:a :A :b :B :c "hello world"}]

    (enc/qb 10000
      (let [pk default-edn-packer]          (unpack pk (pack pk data)))
      (let [pk default-transit-json-packer] (unpack pk (pack pk data))))))

;;;; Server API

(def ^:private next-idx! (enc/idx-fn))

(declare
  ^:private send-buffered-server-evs>ws-clients!
  ^:private send-buffered-server-evs>ajax-clients!
  ^:private default-client-side-ajax-timeout-ms)

(defn allow-origin?
  "Alpha, subject to change.
  Returns true iff given Ring request is allowed by `allowed-origins`.
  `allowed-origins` may be `:all` or #{<origin> ...}."

  [allowed-origins ring-req]
  (enc/cond
    (= allowed-origins :all) true

    :let
    [headers (get ring-req :headers)
     origin  (get headers  "origin" :nx)
     have-origin? (not= origin      :nx)]

    (and
      have-origin?
      (contains? (set allowed-origins) origin))
    true

    ;; As per OWASP CSRF Prevention Cheat Sheet
    :let [referer (get headers "referer" "")]

    (and
      (not have-origin?)
      (enc/rsome #(str/starts-with? referer (str % "/")) allowed-origins))
    true

    :else false))

(comment
  ;; good (pass)
  (allow-origin? :all                 {:headers {"origin"  "http://site.com"}})
  (allow-origin? #{"http://site.com"} {:headers {"origin"  "http://site.com"}})
  (allow-origin? #{"http://site.com"} {:headers {"referer" "http://site.com/"}})

  ;; bad (fail)
  (allow-origin? #{"http://site.com"} {:headers nil})
  (allow-origin? #{"http://site.com"} {:headers {"origin"  "http://attacker.com"}})
  (allow-origin? #{"http://site.com"} {:headers {"referer" "http://attacker.com/"}})
  (allow-origin? #{"http://site.com"} {:headers {"referer" "http://site.com.attacker.com/"}}))

(defn make-channel-socket-server!
  "Takes a web server adapter[1] and returns a map with keys:

    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
    :send-fn                     ; (fn [user-id ev] for server>user push.
    :ajax-post-fn                ; (fn [ring-req])  for Ring CSRF-POST + chsk URL.
    :ajax-get-or-ws-handshake-fn ; (fn [ring-req])  for Ring GET + chsk URL.

    :connected-uids ;             Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}}).
    :send-buffers   ; Implementation detail, read-only (atom {:ws #{_} :ajax #{_} :any #{_}}).

  Security options:

    :allowed-origins   ; e.g. #{\"http://site.com\" ...}, defaults to :all. ; Alpha

    :csrf-token-fn     ; ?(fn [ring-req]) -> CSRF-token for Ajax POSTs and WS handshake.
                       ; CSRF check will be skipped iff nil (NOT RECOMMENDED!).

    :authorized?-fn    ; ?(fn [ring-req]) -> When non-nil, (authorized?-fn <ring-req>)
                       ; must return truthy, otherwise connection requests will be
                       ; rejected with (unauthorized-fn <ring-req>) response.
                       ;
                       ; May check Authroization HTTP header, etc.

    :?unauthorized-fn  ; An alternative API to `authorized?-fn`+`unauthorized-fn` pair.
                       ; ?(fn [ring-req)) -> <?rejection-resp>. I.e. when return value
                       ; is non-nil, connection requests will be rejected with that
                       ; non-nil value.

  Other common options:

    :user-id-fn        ; (fn [ring-req]) -> unique user-id for server>user push.
    :handshake-data-fn ; (fn [ring-req]) -> arb user data to append to handshake evs.
    :ws-kalive-ms      ; Ping to keep a WebSocket conn alive if no activity
                       ; w/in given msecs. Should be different to client's :ws-kalive-ms.
    :lp-timeout-ms     ; Timeout (repoll) long-polling Ajax conns after given msecs.
    :send-buf-ms-ajax  ; [2]
    :send-buf-ms-ws    ; [2]
    :packer            ; :edn (default), or an IPacker implementation.

  [1] e.g. `(taoensso.sente.server-adapters.http-kit/get-sch-adapter)` or
           `(taoensso.sente.server-adapters.immutant/get-sch-adapter)`.
      You must have the necessary web-server dependency in your project.clj and
      the necessary entry in your namespace's `ns` form.

  [2] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."

  [web-server-ch-adapter
   & [{:keys [recv-buf-or-n ws-kalive-ms lp-timeout-ms
              send-buf-ms-ajax send-buf-ms-ws
              user-id-fn bad-csrf-fn bad-origin-fn csrf-token-fn
              handshake-data-fn packer allowed-origins
              authorized?-fn unauthorized-fn ?unauthorized-fn]

       :or   {recv-buf-or-n    (async/sliding-buffer 1000)
              ws-kalive-ms     (enc/ms :secs 25) ; < Heroku 55s timeout
              lp-timeout-ms    (enc/ms :secs 20) ; < Heroku 30s timeout
              send-buf-ms-ajax 100
              send-buf-ms-ws   30
              user-id-fn      (fn [ ring-req] (get-in ring-req [:session :uid]))
              bad-csrf-fn     (fn [_ring-req] {:status 403 :body "Bad CSRF token"})
              bad-origin-fn   (fn [_ring-req] {:status 403 :body "Unauthorized origin"})
              unauthorized-fn (fn [_ring-req] {:status 401 :body "Unauthorized request"})
              csrf-token-fn
              (fn [ring-req]
                (or (:anti-forgery-token ring-req)
                  (get-in ring-req [:session :csrf-token])
                  (get-in ring-req [:session :ring.middleware.anti-forgery/anti-forgery-token])
                  (get-in ring-req [:session "__anti-forgery-token"])
                  #_:sente/no-reference-csrf-token
                  ))

              handshake-data-fn (fn [ring-req] nil)
              packer :edn
              allowed-origins :all}}]]

  (have? enc/pos-int? send-buf-ms-ajax send-buf-ms-ws)
  (have? #(satisfies? interfaces/IServerChanAdapter %) web-server-ch-adapter)

  (let [max-ms default-client-side-ajax-timeout-ms]
    (when (>= lp-timeout-ms max-ms)
      (throw
        (ex-info (str ":lp-timeout-ms must be < " max-ms)
          {:lp-timeout-ms lp-timeout-ms
           :default-client-side-ajax-timeout-ms max-ms}))))

  (let [allowed-origins (have [:or set? #{:all}] allowed-origins)
        packer  (coerce-packer packer)
        ch-recv (chan recv-buf-or-n)

        user-id-fn
        (fn [ring-req client-id]
          ;; Allow uid to depend (in part or whole) on client-id. Be cautious
          ;; of security implications.
          (or (user-id-fn (assoc ring-req :client-id client-id)) ::nil-uid))

        ;; :ws udts used for ws-kalive (to check for activity in window period)
        ;; :ajax udts used for lp-timeout (as a way to check active conn identity)
        conns_          (atom {:ws  {} :ajax  {}}) ; {<uid> {<client-id> [<?sch> <udt>]}}
        send-buffers_   (atom {:ws  {} :ajax  {}}) ; {<uid> [<buffered-evs> <#{ev-uuids}>]}
        connected-uids_ (atom {:ws #{} :ajax #{} :any #{}}) ; Public

        upd-conn!
        (fn
          ([conn-type uid client-id] ; Update udt
           (swap-in! conns_ [conn-type uid client-id]
             (fn [?v]
               (let [[?sch _udt] ?v
                     new-udt (enc/now-udt)]
                 (enc/swapped
                   [?sch new-udt]
                   {:init? (nil? ?v) :udt new-udt :?sch ?sch})))))

          ([conn-type uid client-id new-?sch] ; Update sch + udt
           (swap-in! conns_ [conn-type uid client-id]
             (fn [?v]
               (let [new-udt (enc/now-udt)]
                 (enc/swapped
                   [new-?sch new-udt]
                   {:init? (nil? ?v) :udt new-udt :?sch new-?sch}))))))

        connect-uid!
        (fn [conn-type uid] {:pre [(have? uid)]}
          (let [newly-connected?
                (swap-in! connected-uids_ []
                  (fn [{:keys [ws ajax any] :as old-m}]
                    (let [new-m
                          (case conn-type
                            :ws   {:ws (conj ws uid) :ajax ajax            :any (conj any uid)}
                            :ajax {:ws ws            :ajax (conj ajax uid) :any (conj any uid)})]
                      (swapped new-m
                        (let [old-any (:any old-m)
                              new-any (:any new-m)]
                          (when (and (not (contains? old-any uid))
                                          (contains? new-any uid))
                            :newly-connected))))))]
            newly-connected?))

        upd-connected-uid!
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
                          (when (and     (contains? old-any uid)
                                    (not (contains? new-any uid)))
                            :newly-disconnected))))))]

            newly-disconnected?))

        send-fn ; server>user (by uid) push
        (fn [user-id ev & [{:as opts :keys [flush?]}]]
          (let [uid (if (= user-id :sente/all-users-without-uid) ::nil-uid user-id)
                _   (tracef "Chsk send: (->uid %s) %s" uid ev)
                _   (assert uid
                      (str "Support for sending to `nil` user-ids has been REMOVED. "
                           "Please send to `:sente/all-users-without-uid` instead."))
                _   (assert-event ev)

                ev-uuid (enc/uuid-str)

                flush-buffer!
                (fn [conn-type]
                  (when-let
                      [pulled
                       (swap-in! send-buffers_ [conn-type]
                         (fn [m]
                           ;; Don't actually flush unless the event buffered
                           ;; with _this_ send call is still buffered (awaiting
                           ;; flush). This means that we'll have many (go
                           ;; block) buffer flush calls that'll noop. They're
                           ;; cheap, and this approach is preferable to
                           ;; alternatives like flush workers.
                           (let [[_ ev-uuids] (get m uid)]
                             (if (contains? ev-uuids ev-uuid)
                               (swapped
                                 (dissoc m uid)
                                 (get    m uid))
                               (swapped m nil)))))]

                    (let [[buffered-evs ev-uuids] pulled]
                      (have? vector? buffered-evs)
                      (have? set?    ev-uuids)

                      (let [buffered-evs-ppstr (pack packer buffered-evs)]
                        (tracef "buffered-evs-ppstr: %s" buffered-evs-ppstr)
                        (case conn-type
                          :ws   (send-buffered-server-evs>ws-clients! conns_
                                  uid buffered-evs-ppstr upd-conn!)
                          :ajax (send-buffered-server-evs>ajax-clients! conns_
                                  uid buffered-evs-ppstr))))))]

            (if (= ev [:chsk/close]) ; Currently undocumented
              (do
                (debugf "Chsk closing (client may reconnect): %s" uid)
                (when flush?
                  (flush-buffer! :ws)
                  (flush-buffer! :ajax))

                (doseq [[?sch _udt] (vals (get-in @conns_ [:ws uid]))]
                  (when-let [sch ?sch] (interfaces/sch-close! sch)))

                (doseq [[?sch _udt] (vals (get-in @conns_ [:ajax uid]))]
                  (when-let [sch ?sch] (interfaces/sch-close! sch))))

              (do
                ;; Buffer event
                (doseq [conn-type [:ws :ajax]]
                  (swap-in! send-buffers_ [conn-type uid]
                    (fn [?v]
                      (if-not ?v
                        [[ev] #{ev-uuid}]
                        (let [[buffered-evs ev-uuids] ?v]
                          [(conj buffered-evs ev)
                           (conj ev-uuids     ev-uuid)])))))

                ;;; Flush event buffers after relevant timeouts:
                ;; * May actually flush earlier due to another timeout.
                ;; * We send to _all_ of a uid's connections.
                ;; * Broadcasting is possible but I'd suggest doing it rarely,
                ;;   and only to users we know/expect are actually online.
                ;;
                (if flush?
                  (do
                    (flush-buffer! :ws)
                    (flush-buffer! :ajax))
                  (let [ws-timeout   (async/timeout send-buf-ms-ws)
                        ajax-timeout (async/timeout send-buf-ms-ajax)]
                    (go
                      (<! ws-timeout)
                      (flush-buffer! :ws))
                    (go
                      (<! ajax-timeout)
                      (flush-buffer! :ajax)))))))

          ;; Server-side send is async so nothing useful to return (currently
          ;; undefined):
          nil)

        bad-csrf?
        (fn [ring-req]
          (if (nil? csrf-token-fn) ; Provides a way to disable CSRF check
            false
            (if-let [reference-csrf-token (csrf-token-fn ring-req)]
              (let [csrf-token-from-client
                    (or
                      (get-in ring-req [:params    :csrf-token])
                      (get-in ring-req [:headers "x-csrf-token"])
                      (get-in ring-req [:headers "x-xsrf-token"]))]

                (not
                  (enc/const-str=
                    reference-csrf-token
                    csrf-token-from-client)))

              true ; By default fail if no CSRF token
              )))

        unauthorized?
        (fn [ring-req]
          (if (nil? authorized?-fn)
            false
            (not (authorized?-fn ring-req))))

        ;; nnil if connection attempt should be rejected
        possible-rejection-resp
        (fn [ring-req]
          (enc/cond
            (bad-csrf?   ring-req)
            (bad-csrf-fn ring-req)

            (not (allow-origin? allowed-origins ring-req))
            (bad-origin-fn                      ring-req)

            (unauthorized?   ring-req)
            (unauthorized-fn ring-req)

            :if-some [unauthorized-resp (when-let [uf ?unauthorized-fn]
                                          (uf ring-req))]
            unauthorized-resp

            :else nil))

        ev-msg-const
        {:ch-recv        ch-recv
         :send-fn        send-fn
         :connected-uids connected-uids_
         :send-buffers   send-buffers_}]

    {:ch-recv        ch-recv
     :send-fn        send-fn
     :connected-uids connected-uids_
     :send-buffers   send-buffers_

     ;; Does not participate in `conns_` (has specific req->resp)
     :ajax-post-fn
     (fn [ring-req]
       (enc/cond
         :if-let [resp (possible-rejection-resp ring-req)] resp
         :else
         (interfaces/ring-req->server-ch-resp web-server-ch-adapter ring-req
           {:on-open
            (fn [server-ch websocket?]
              (assert (not websocket?))
              (let [params        (get ring-req :params)
                    ppstr         (get params   :ppstr)
                    client-id     (get params   :client-id)
                    [clj has-cb?] (unpack packer ppstr)
                    reply-fn
                    (let [replied?_ (atom false)]
                      (fn [resp-clj] ; Any clj form
                        (when (compare-and-set! replied?_ false true)
                          (tracef "Chsk send (ajax post reply): %s" resp-clj)
                          (interfaces/sch-send! server-ch websocket?
                            (pack packer resp-clj)))))]

                (put-server-event-msg>ch-recv! ch-recv
                  (merge ev-msg-const
                    {;; Note that the client-id is provided here just for the
                     ;; user's convenience. non-lp-POSTs don't actually need a
                     ;; client-id for Sente's own implementation:
                     :client-id client-id #_"unnecessary-for-non-lp-POSTs"
                     :ring-req  ring-req
                     :event     clj
                     :uid       (user-id-fn ring-req client-id)
                     :?reply-fn (when has-cb? reply-fn)}))

                (if has-cb?
                  (when-let [ms lp-timeout-ms]
                    (go
                      (<! (async/timeout ms))
                      (reply-fn :chsk/timeout)))
                  (reply-fn :chsk/dummy-cb-200))))})))

     ;; Ajax handshake/poll, or WebSocket handshake
     :ajax-get-or-ws-handshake-fn
     (fn [ring-req]
       (let [sch-uuid   (enc/uuid-str 6)
             params     (get ring-req :params)
             client-id  (get params   :client-id)
             uid        (user-id-fn    ring-req client-id)

             receive-event-msg! ; Partial
             (fn self
               ([event          ] (self event nil))
               ([event ?reply-fn]
                (put-server-event-msg>ch-recv! ch-recv
                  (merge ev-msg-const
                    {:client-id client-id
                     :ring-req  ring-req
                     :event     event
                     :?reply-fn ?reply-fn
                     :uid       uid}))))

             send-handshake!
             (fn [server-ch websocket?]
               (tracef "send-handshake!")
               (let [?handshake-data (handshake-data-fn ring-req)
                     handshake-ev
                     (if (nil? ?handshake-data) ; Micro optimization
                       [:chsk/handshake [uid nil]]
                       [:chsk/handshake [uid nil ?handshake-data]])]
                 (interfaces/sch-send! server-ch websocket?
                   (pack packer handshake-ev))))]

         (enc/cond

           (str/blank? client-id)
           (let [err-msg "Client's Ring request doesn't have a client id. Does your server have the necessary keyword Ring middleware (`wrap-params` & `wrap-keyword-params`)?"]
             (errorf (str err-msg ": %s") ring-req) ; Careful re: % in req
             (throw (ex-info err-msg {:ring-req ring-req})))

           :if-let [resp (possible-rejection-resp ring-req)] resp
           :else
           (interfaces/ring-req->server-ch-resp web-server-ch-adapter ring-req
             {:on-open
              (fn [server-ch websocket?]
                (if websocket?

                  ;; WebSocket handshake
                  (let [_ (tracef "New WebSocket channel: %s (%s)" uid sch-uuid)
                        updated-conn (upd-conn! :ws uid client-id server-ch)
                        udt-open     (:udt updated-conn)]

                    (when (connect-uid! :ws uid)
                      (receive-event-msg! [:chsk/uidport-open uid]))

                    (send-handshake! server-ch websocket?)

                    ;; Start ws-kalive loop
                    ;; This also works to gc ws conns that were suddenly
                    ;; terminated (e.g. by turning on airplane mode)
                    (when-let [ms ws-kalive-ms]
                      (go-loop [udt-t0 udt-open]
                        (<! (async/timeout ms))
                        (when-let [[_sch udt-t1] (get-in @conns_ [:ws uid client-id])]
                          (when (interfaces/sch-open? server-ch)
                            ;; (assert (= _sch server-ch))
                            (when (= udt-t1 udt-t0)
                              ;; Ref. issue #230:
                              ;; We've seen no send/recv activity on this
                              ;; conn w/in our kalive window so send a ping
                              ;; ->client (should auto-close conn if it's
                              ;; gone dead).
                              (interfaces/sch-send! server-ch websocket?
                                (pack packer :chsk/ws-ping)))
                            (recur udt-t1))))))

                  ;; Ajax handshake/poll
                  (let [_ (tracef "New Ajax handshake/poll: %s (%s)" uid sch-uuid)
                        updated-conn (upd-conn! :ajax uid client-id server-ch)
                        udt-open     (:udt updated-conn)
                        handshake?   (or (:init? updated-conn) (:handshake? params))]

                    (when (connect-uid! :ajax uid)
                      (receive-event-msg! [:chsk/uidport-open uid]))

                    (if handshake?
                      ;; Client will immediately repoll
                      (send-handshake! server-ch websocket?)

                      (when-let [ms lp-timeout-ms]
                        (go
                          (<! (async/timeout ms))
                          (when-let [[_sch udt-t1] (get-in @conns_ [:ajax uid client-id])]
                            (when (= udt-t1 udt-open)
                              ;; (assert (= _sch server-ch))
                              ;; Appears to still be the active sch
                              (interfaces/sch-send! server-ch websocket?
                                (pack packer :chsk/timeout))))))))))

              :on-msg
              (fn [server-ch websocket? req-ppstr]
                (assert websocket?)
                (upd-conn! :ws uid client-id)
                (let [[clj ?cb-uuid] (unpack packer req-ppstr)]
                  (receive-event-msg! clj ; Should be ev
                    (when ?cb-uuid
                      (fn reply-fn [resp-clj] ; Any clj form
                        (tracef "Chsk send (ws reply): %s" resp-clj)
                        ;; true iff apparent success:
                        (interfaces/sch-send! server-ch websocket?
                          (pack packer resp-clj ?cb-uuid)))))))

              :on-close ; We rely on `on-close` to trigger for _every_ conn!
              (fn [server-ch websocket? _status]
                ;; Note that `status` type varies with underlying web server
                (let [conn-type (if websocket? :ws :ajax)
                      _ (tracef "%s channel closed: %s (%s)"
                          (if websocket? "WebSocket" "Ajax")
                          uid sch-uuid)

                      updated-conn (upd-conn! conn-type uid client-id nil)
                      udt-close    (:udt updated-conn)]

                  ;; Allow some time for possible reconnects (repoll,
                  ;; sole window refresh, etc.):
                  (go
                    (<! (async/timeout 5000))
                    (let [[_?sch udt-t1] (get-in @conns_ [conn-type uid client-id])]

                      (when @debug-mode?_
                        (debugf "close-timeout: %s %s %s %s" conn-type uid
                          sch-uuid [(= udt-t1 udt-close) udt-t1 udt-close]))

                      (when (= udt-t1 udt-close)
                        (let [disconnect? ; Removed entry for client-id?
                              (swap-in! conns_ [conn-type uid client-id]
                                (fn [[_sch udt-t1]]
                                  (if (= udt-t1 udt-close)
                                    (swapped :swap/dissoc true)
                                    (swapped [_sch udt-t1] false))))]

                          (when disconnect?

                            ;; Potentially remove entry for uid
                            (swap-in! conns_ [conn-type uid]
                              (fn [?m] (if (empty? ?m) :swap/dissoc ?m)))

                            (when (upd-connected-uid! uid)
                              (receive-event-msg! [:chsk/uidport-close uid])))))))))

              :on-error
              (fn [server-ch websocket? error]
                (errorf "ring-req->server-ch-resp error: %s (%s)"
                  error uid sch-uuid))}))))}))

(defn- send-buffered-server-evs>ws-clients!
  "Actually pushes buffered events (as packed-str) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-pstr upd-conn!]
  (tracef "send-buffered-server-evs>ws-clients!: %s" buffered-evs-pstr)
  (doseq [[client-id [?sch _udt]] (get-in @conns_ [:ws uid])]
    (when-let [sch ?sch]
      (upd-conn! :ws uid client-id)
      (interfaces/sch-send! sch :websocket buffered-evs-pstr))))

(defn- send-buffered-server-evs>ajax-clients!
  "Actually pushes buffered events (as packed-str) to all uid's Ajax conns.
  Allows some time for possible Ajax poller reconnects."
  [conns_ uid buffered-evs-pstr]
  (tracef "send-buffered-server-evs>ajax-clients!: %s" buffered-evs-pstr)
  (let [ms-backoffs [90 180 360 720 1440] ; Mean 2790s
        ;; All connected/possibly-reconnecting client uuids:
        client-ids-unsatisfied (keys (get-in @conns_ [:ajax uid]))]

    (when-not (empty? client-ids-unsatisfied)
      ;; (tracef "client-ids-unsatisfied: %s" client-ids-unsatisfied)
      (go-loop [n 0 client-ids-satisfied #{}]
        (let [?pulled ; nil or {<client-id> [<?sch> <udt>]}
              (swap-in! conns_ [:ajax uid]
                (fn [m] ; {<client-id> [<?sch> <udt>]}
                  (let [ks-to-pull (remove client-ids-satisfied (keys m))]
                    ;; (tracef "ks-to-pull: %s" ks-to-pull)
                    (if (empty? ks-to-pull)
                      (swapped m nil)
                      (swapped
                        (reduce
                          (fn [m k]
                            (let [[?sch udt] (get m k)]
                              ;; Nb don't change udt; for Ajax conns_ we only
                              ;; want udt updated on poll or close, not on
                              ;; activity (as with ws conns_)
                              (assoc m k [nil udt #_(enc/now-udt)])))

                          m ks-to-pull)
                        (select-keys m ks-to-pull))))))]

          (have? [:or nil? map?] ?pulled)

          (let [?newly-satisfied
                (when ?pulled
                  (reduce-kv
                    (fn [s client-id [?sch _udt]]
                      (let [sent?
                            (when-let [sch ?sch]
                              ;; Will noop + return false if sch already closed:
                              (interfaces/sch-send! ?sch (not :websocket)
                                buffered-evs-pstr))]

                        (if sent? (conj s client-id) s)))
                    #{} ?pulled))

                now-satisfied (into client-ids-satisfied ?newly-satisfied)]

            ;; (tracef "now-satisfied: %s" now-satisfied)
            (when-let [ms-backoff (get ms-backoffs n)]
              (when (enc/rsome (complement now-satisfied) client-ids-unsatisfied)
                (let [ms-timeout (+ ms-backoff (rand-int ms-backoff))]
                  ;; Allow some time for possible poller reconnects:
                  (<! (async/timeout ms-timeout))
                  (recur (inc n) now-satisfied))))))))))

;;;; Client API

#?(:cljs (def ajax-lite "Alias of `taoensso.encore/ajax-lite`" enc/ajax-lite))

   (defprotocol IChSocket
     (-chsk-connect!    [chsk])
     (-chsk-disconnect! [chsk reason])
     (-chsk-reconnect!  [chsk])
     (-chsk-send!       [chsk ev opts]))

     (defn chsk-connect!    [chsk] (-chsk-connect!    chsk))
     (defn chsk-disconnect! [chsk] (-chsk-disconnect! chsk :requested-disconnect))
     (defn chsk-reconnect! "Useful for reauthenticating after login/logout, etc."
       [chsk] (-chsk-reconnect! chsk))
     (def chsk-destroy! "Deprecated" chsk-disconnect!)

   (defn chsk-send!
     "Sends `[ev-id ev-?data :as event]`, returns true on apparent success."
     ([chsk ev] (chsk-send! chsk ev {}))
     ([chsk ev ?timeout-ms ?cb] (chsk-send! chsk ev {:timeout-ms ?timeout-ms
                                                     :cb         ?cb}))
     ([chsk ev opts]
      (tracef "Chsk send: (%s) %s" (assoc opts :cb (boolean (:cb opts))) ev)
      (-chsk-send! chsk ev opts)))

   (defn- chsk-send->closed! [?cb-fn]
     (warnf "Chsk send against closed chsk.")
     (when ?cb-fn (?cb-fn :chsk/closed))
     false)

   (defn- assert-send-args [x ?timeout-ms ?cb]
     (assert-event x)
     (assert (or (and (nil? ?timeout-ms) (nil? ?cb))
                 (and (enc/nat-int? ?timeout-ms)))
       (str "cb requires a timeout; timeout-ms should be a +ive integer: " ?timeout-ms))
     (assert (or (nil? ?cb) (ifn? ?cb) (enc/chan? ?cb))
       (str "cb should be nil, an ifn, or a channel: " (type ?cb))))

   (defn- pull-unused-cb-fn! [cbs-waiting_ ?cb-uuid]
     (when-let [cb-uuid ?cb-uuid]
       (swap-in! cbs-waiting_ [cb-uuid]
         (fn [?f] (swapped :swap/dissoc ?f)))))

   (defn- swap-chsk-state!
     "Atomically swaps the value of chk's :state_ atom."
     [chsk f]
     (let [[old-state new-state]
           (swap-in! (:state_ chsk)
             (fn [old-state]
               (let [new-state (f old-state)
                     new-state
                     (if (:first-open? old-state)
                       (assoc new-state :first-open? false)
                       new-state)

                     new-state
                     (if (:open? new-state)
                       (dissoc new-state :udt-next-reconnect)
                       new-state)]

                 (swapped new-state [old-state new-state]))))]

       (when (not= old-state new-state)
         (let [output [old-state new-state]]
           ;; (debugf "Chsk state change: %s" output)
           (put! (get-in chsk [:chs :state]) [:chsk/state output])
           output))))

   (defn- chsk-state->closed [state reason]
     (have? map? state)
     (have? [:el #{:requested-disconnect
                   :requested-reconnect
                   :downgrading-ws-to-ajax
                   :unexpected}] reason)
     (if (or (:open? state) (not= reason :unexpected))
       (-> state
         (dissoc :udt-next-reconnect)
         (assoc
           :open? false
           :last-close {:udt (enc/now-udt) :reason reason}))
       state))

   (defn- cb-chan-as-fn
     "Experimental, undocumented. Allows a core.async channel to be provided
     instead of a cb-fn. The channel will receive values of form
     [<event-id>.cb <reply>]."
     [?cb ev]
     (if (or (nil? ?cb) (ifn? ?cb))
       ?cb
       (do
         (have? enc/chan? ?cb)
         (assert-event ev)
         (let [[ev-id _] ev
               cb-ch ?cb]
           (fn [reply]
             (put! cb-ch
               [(keyword (str (enc/as-qname ev-id) ".cb"))
                reply]))))))

   (defn- receive-buffered-evs! [chs clj]
     (tracef "receive-buffered-evs!: %s" clj)
     (let [buffered-evs (have vector? clj)]
       (doseq [ev buffered-evs]
         (assert-event ev)
         ;; Should never receive :chsk/* events from server here:
         (let [[id] ev] (assert (not= (namespace id) "chsk")))
         (put! (:<server chs) ev))))

   (defn- handshake? [x]
     (and (vector? x) ; Nb support arb input (e.g. cb replies)
       (let [[x1] x] (= x1 :chsk/handshake))))

   (defn- receive-handshake! [chsk-type chsk clj]
     (have? [:el #{:ws :ajax}] chsk-type)
     (have? handshake? clj)
     (tracef "receive-handshake! (%s): %s" chsk-type clj)
     (let [[_ [?uid _ ?handshake-data]] clj
           {:keys [chs ever-opened?_]} chsk
           first-handshake? (compare-and-set! ever-opened?_ false true)
           new-state
           {:type           chsk-type ; :auto -> e/o #{:ws :ajax}, etc.
            :open?          true
            :ever-opened?   true
            :uid            ?uid
            :handshake-data ?handshake-data
            :first-open?    first-handshake?}

           handshake-ev
           [:chsk/handshake
            [?uid nil ?handshake-data first-handshake?]]]

       (assert-event handshake-ev)
       (swap-chsk-state! chsk #(merge % new-state))
       (put! (:internal chs) handshake-ev)

       :handled))

#?(:clj
   (defmacro ^:private elide-require
     "Experimental. The presence of `js/require` calls can cause issues with
    React Native, even if they never execute. Currently no other known
    workarounds. Ref. https://github.com/ptaoussanis/sente/issues/247."
     [& body]
     (when-not (enc/get-sys-val "SENTE_ELIDE_JS_REQUIRE")
       `(do ~@body))))

#?(:cljs
   (def ^:private ?node-npm-websocket_
     "nnil iff the websocket npm library[1] is available.
     Easiest way to install:
       1. Add the lein-npm[2] plugin to your `project.clj`,
       2. Add: `:npm {:dependencies [[websocket \"1.0.23\"]]}`

     [1] Ref. https://www.npmjs.com/package/websocket
     [2] Ref. https://github.com/RyanMcG/lein-npm"

     ;; This `let` silliness intended to work around React Native's
     ;; static analysis tool, to prevent it from detecting a
     ;; missing package.
     ;;
     ;; Ref. https://github.com/ptaoussanis/sente/issues/247#issuecomment-555219121
     ;;
     (let [make-package-name (fn [prefix] (str prefix "socket"))
           require-fn
           (if (exists? js/require)
             js/require
             (constantly :no-op))]

       (delay ; Eager eval causes issues with React Native, Ref. #247,
         (elide-require ; TODO is this now safe to remove?
           (when (and node-target? (exists? js/require))
             (try
               (require-fn (make-package-name "web"))
               ;; In particular, catch 'UnableToResolveError'
               (catch :default e
                 ;; (errorf e "Unable to load npm websocket lib")
                 nil))))))))

#?(:clj
   (defn- create-java-client-websocket!
     [{:as opts :keys [onerror-fn onmessage-fn onclose-fn uri-str headers]}]
     (let [uri (java.net.URI. uri-str)

           ;; headers
           ;; (ImmutableMap/of
           ;;   "Origin"  "http://localhost:3200"
           ;;   "Referer" "http://localhost:3200"
           ;;   "Sec-WebSocket-Extensions" "permessage-deflate; client_max_window_bits"
           ;;   )

           ws-client
           (proxy [WebSocketClient] [^java.net.URI uri ^java.util.Map headers]
             (onOpen    [^org.java_websocket.handshake.ServerHandshake handshakedata] nil)
             (onError   [ex]                 (onerror-fn   ex))
             (onMessage [^String message]    (onmessage-fn message))
             (onClose   [code reason remote] (onclose-fn   code reason remote)))]

       ;; JS client attempts to connect right away at construction time.
       ;; Java client doesn't need to, but we'll do anyway for consistency.
       (.connect ws-client)
       (do       ws-client))))

#?(:cljs
   (defn- create-js-client-websocket!
     [{:as opts :keys [onerror-fn onmessage-fn onclose-fn uri-str headers]}]
     (when-let [WebSocket
                (or
                  (enc/oget goog/global           "WebSocket")
                  (enc/oget goog/global           "MozWebSocket")
                  (enc/oget @?node-npm-websocket_ "w3cwebsocket"))]

       (let [socket (WebSocket. uri-str)]
         (doto socket
           (aset "onerror"   onerror-fn)
           (aset "onmessage" onmessage-fn) ; Nb receives both push & cb evs!
           ;; Fires repeatedly (on each connection attempt) while server is down:
           (aset "onclose"   onclose-fn))
         socket))))

(defn- create-websocket! [{:as opts :keys [onerror-fn onmessage-fn onclose-fn uri-str headers]}]
  #?(:cljs (create-js-client-websocket!   opts)
     :clj  (create-java-client-websocket! opts)))

(defrecord ChWebSocket
    ;; WebSocket-only IChSocket implementation
    ;; Handles (re)connections, cbs, etc.

    [client-id chs params headers packer url ws-kalive-ms
     state_ ; {:type _ :open? _ :uid _ :csrf-token _ ...}
     instance-handle_ retry-count_ ever-opened?_
     backoff-ms-fn ; (fn [nattempt]) -> msecs
     cbs-waiting_ ; {<cb-uuid> <fn> ...}
     socket_
     udt-last-comms_]

  IChSocket
  (-chsk-disconnect! [chsk reason]
    (reset! instance-handle_ nil) ; Disable auto retry
    (swap-chsk-state! chsk #(chsk-state->closed % reason))
    (when-let [s @socket_]
      #?(:clj  (.closeBlocking ^WebSocketClient s)
         :cljs (.close s 1000 "CLOSE_NORMAL"))))

  (-chsk-reconnect! [chsk]
    (-chsk-disconnect! chsk :requested-reconnect)
    (-chsk-connect!    chsk))

  (-chsk-send! [chsk ev opts]
    (let [{?timeout-ms :timeout-ms ?cb :cb :keys [flush?]} opts
          _ (assert-send-args ev ?timeout-ms ?cb)
          ?cb-fn (cb-chan-as-fn ?cb ev)]
      (if-not (:open? @state_) ; Definitely closed
        (chsk-send->closed! ?cb-fn)

        ;; TODO Buffer before sending (but honor `:flush?`)
        (let [?cb-uuid (when ?cb-fn (enc/uuid-str 6))
              ppstr (pack packer ev ?cb-uuid)]

          (when-let [cb-uuid ?cb-uuid]
            (reset-in! cbs-waiting_ [cb-uuid] (have ?cb-fn))
            (when-let [timeout-ms ?timeout-ms]
              (go
                (<! (async/timeout timeout-ms))
                (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
                  (cb-fn* :chsk/timeout)))))

          (try
            #?(:cljs (.send                  @socket_         ppstr)
               :clj  (.send ^WebSocketClient @socket_ ^String ppstr))

            (reset! udt-last-comms_ (enc/now-udt))
            :apparent-success
            (catch #?(:clj Throwable :cljs :default) t
              (errorf t "Chsk send error")
              (when-let [cb-uuid ?cb-uuid]
                (let [cb-fn* (or (pull-unused-cb-fn! cbs-waiting_ cb-uuid)
                                 (have ?cb-fn))]
                  (cb-fn* :chsk/error)))
              false))))))

  (-chsk-connect! [chsk]
    (let [instance-handle (reset! instance-handle_ (enc/uuid-str))
          have-handle? (fn [] (= @instance-handle_ instance-handle))
          connect-fn
          (fn connect-fn []
            (when (have-handle?)
              (let [retry-fn
                    (fn [] ; Backoff then recur
                      (when (have-handle?)
                        (let [retry-count* (swap! retry-count_ inc)
                              backoff-ms (backoff-ms-fn retry-count*)
                              udt-next-reconnect (+ (enc/now-udt) backoff-ms)]
                          (warnf "Chsk is closed: will try reconnect attempt (%s) in %s ms"
                            retry-count* backoff-ms)
                          #?(:cljs (.setTimeout goog/global connect-fn backoff-ms)
                             :clj  (go
                                     (<! (async/timeout backoff-ms))
                                     (connect-fn)))

                          (swap-chsk-state! chsk
                            #(assoc % :udt-next-reconnect udt-next-reconnect)))))

                    onerror-fn
                    #?(:cljs
                       (fn [ws-ev]
                         (errorf ; ^:meta {:raw-console? true}
                           "WebSocket error: %s"
                           (try
                             (js->clj          ws-ev)
                             (catch :default _ ws-ev)))

                         (swap-chsk-state! chsk
                           #(assoc % :last-ws-error
                              {:udt (enc/now-udt), :ev ws-ev})))

                       :clj
                       (fn [ex]
                         (errorf ex "WebSocket error")
                         (swap-chsk-state! chsk
                           #(assoc % :last-ws-error
                              {:udt (enc/now-udt), :ex ex}))))

                    onmessage-fn ; Nb receives both push & cb evs!
                    (fn #?(:cljs [ws-ev] :clj [ppstr])
                      (let [ppstr #?(:clj            ppstr
                                     :cljs (enc/oget ws-ev "data"))

                            ;; `clj` may/not satisfy `event?` since
                            ;; we also receive cb replies here. This
                            ;; is why we prefix pstrs to indicate
                            ;; whether they're wrapped or not
                            [clj ?cb-uuid] (unpack packer ppstr)]

                        (reset! udt-last-comms_ (enc/now-udt))

                        (or
                          (when (handshake? clj)
                            (receive-handshake! :ws chsk clj)
                            (reset! retry-count_ 0)
                            :handshake)

                          (when (= clj :chsk/ws-ping)
                            (put! (:<server chs) [:chsk/ws-ping])
                            :noop)

                          (if-let [cb-uuid ?cb-uuid]
                            (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting_
                                             cb-uuid)]
                              (cb-fn clj)
                              (warnf "Cb reply w/o local cb-fn: %s" clj))
                            (let [buffered-evs clj]
                              (receive-buffered-evs! chs buffered-evs))))))

                    ;; Fires repeatedly (on each connection attempt) while
                    ;; server is down:
                    onclose-fn
                    (fn #?(:cljs [ws-ev] :clj [code reason remote])
                      (let [last-ws-close
                            #?(:clj
                               {:udt    (enc/now-udt)
                                :clean? (= code org.java_websocket.framing.CloseFrame/NORMAL)
                                :code   code
                                :reason reason}

                               :cljs
                               {:udt    (enc/now-udt)
                                :ev     ws-ev
                                :clean? (enc/oget ws-ev "wasClean")
                                :code   (enc/oget ws-ev "code")
                                :reason (enc/oget ws-ev "reason")})]

                        ;; Firefox calls "onclose" while unloading,
                        ;; Ref. http://goo.gl/G5BYbn:
                        (if (:clean? last-ws-close)
                          (do
                            (debugf "Clean WebSocket close, will not attempt reconnect")
                            (swap-chsk-state! chsk
                              #(assoc % :last-ws-close last-ws-close)))
                          (do
                            (swap-chsk-state! chsk
                              #(assoc (chsk-state->closed % :unexpected)
                                 :last-ws-close last-ws-close))
                            (retry-fn)))))

                    ?socket
                    (try
                      (let [csrf-token-or-fn (:csrf-token @state_)
                            csrf-token       (if (fn? csrf-token-or-fn)
                                               (csrf-token-or-fn)
                                               csrf-token-or-fn)]
                        (create-websocket!
                          {:onerror-fn   onerror-fn
                           :onmessage-fn onmessage-fn
                           :onclose-fn   onclose-fn
                           :headers      headers
                           :uri-str
                           (enc/merge-url-with-query-string url
                             (merge params ; 1st (don't clobber impl.):
                               {:client-id client-id
                                :csrf-token csrf-token}))}))
                      (catch #?(:clj Throwable :cljs :default) t
                        (errorf t "WebSocket error")
                        nil))]

                (if-not ?socket
                  (retry-fn) ; Couldn't even get a socket
                  (do
                    ;; Clean up the old socket if any exists
                    (when-let [old-socket @socket_]
                      #?(:clj  (.close ^WebSocketClient old-socket)
                         :cljs (.close                  old-socket)))

                    (reset! socket_ ?socket))))))]

      (when-let [ms ws-kalive-ms]
        (go-loop []
          (let [udt-t0 @udt-last-comms_]
            (<! (async/timeout ms))
            (when (have-handle?)
              (let [udt-t1 @udt-last-comms_]
                (when (= udt-t0 udt-t1)
                  ;; Ref. issue #259:
                  ;; We've seen no send/recv activity on this
                  ;; conn w/in our kalive window so send a ping
                  ;; ->server (should auto-close conn if it's
                  ;; gone dead). The server generally sends pings so
                  ;; this should be rare. Mostly here to help clients
                  ;; identify conns that were suddenly dropped.

                  (-chsk-send! chsk [:chsk/ws-ping] {:flush? true})))
              (recur)))))

      (reset! retry-count_ 0)
      (connect-fn)
      chsk)))

(defn- new-ChWebSocket [opts csrf-token]
  (map->ChWebSocket
    (merge
      {:state_ (atom {:type :ws :open? false :ever-opened? false :csrf-token csrf-token})
       :instance-handle_ (atom nil)
       :retry-count_     (atom 0)
       :ever-opened?_    (atom false)
       :cbs-waiting_     (atom {})
       :socket_          (atom nil)
       :udt-last-comms_  (atom nil)}
      opts)))

(def ^:private default-client-side-ajax-timeout-ms
  "We must set *some* client-side timeout otherwise an unpredictable (and
  probably too short) browser default will be used. Must be > server's
  :lp-timeout-ms."
  (enc/ms :secs 60))

#?(:cljs
   (defrecord ChAjaxSocket
       ;; Ajax-only IChSocket implementation
       ;; Handles (re)polling, etc.

       [client-id chs params packer url state_
        instance-handle_ ever-opened?_
        backoff-ms-fn
        ajax-opts curr-xhr_]

     IChSocket
     (-chsk-disconnect! [chsk reason]
       (reset! instance-handle_ nil) ; Disable auto retry
       (swap-chsk-state! chsk #(chsk-state->closed % reason))
       (when-let [x @curr-xhr_] (.abort x)))

     (-chsk-reconnect! [chsk]
       (-chsk-disconnect! chsk :requested-reconnect)
       (-chsk-connect!    chsk))

     (-chsk-send! [chsk ev opts]
       (let [{?timeout-ms :timeout-ms ?cb :cb :keys [flush?]} opts
             _ (assert-send-args ev ?timeout-ms ?cb)
             ?cb-fn (cb-chan-as-fn ?cb ev)]
         (if-not (:open? @state_) ; Definitely closed
           (chsk-send->closed! ?cb-fn)

           ;; TODO Buffer before sending (but honor `:flush?`)
           (let [csrf-token (:csrf-token @state_)]
             (ajax-lite url
               (merge ajax-opts
                 {:method     :post
                  :timeout-ms (or ?timeout-ms (:timeout-ms ajax-opts)
                                  default-client-side-ajax-timeout-ms)
                  :resp-type  :text ; We'll do our own pstr decoding
                  :headers
                  (merge
                    (:headers ajax-opts) ; 1st (don't clobber impl.)
                    {:X-CSRF-Token csrf-token})

                  :params
                  (let [ppstr (pack packer ev (when ?cb-fn :ajax-cb))]
                    (merge params ; 1st (don't clobber impl.):
                      {:udt        (enc/now-udt) ; Force uncached resp

                       ;; A duplicate of X-CSRF-Token for user's convenience
                       ;; and for back compatibility with earlier CSRF docs:
                       :csrf-token csrf-token

                       ;; Just for user's convenience here. non-lp-POSTs
                       ;; don't actually need a client-id for Sente's own
                       ;; implementation:
                       :client-id  client-id

                       :ppstr      ppstr}))})

               (fn ajax-cb [{:keys [?error ?content]}]
                 (if ?error
                   (if (= ?error :timeout)
                     (when ?cb-fn (?cb-fn :chsk/timeout))
                     (do
                       (swap-chsk-state! chsk
                         #(chsk-state->closed % :unexpected))
                       (when ?cb-fn (?cb-fn :chsk/error))))

                   (let [content ?content
                         resp-ppstr content
                         [resp-clj _] (unpack packer resp-ppstr)]
                     (if ?cb-fn
                       (?cb-fn resp-clj)
                       (when (not= resp-clj :chsk/dummy-cb-200)
                         (warnf "Cb reply w/o local cb-fn: %s" resp-clj)))
                     (swap-chsk-state! chsk #(assoc % :open? true))))))

             :apparent-success))))

     (-chsk-connect! [chsk]
       (let [instance-handle (reset! instance-handle_ (enc/uuid-str))
             have-handle? (fn [] (= @instance-handle_ instance-handle))
             poll-fn ; async-poll-for-update-fn
             (fn poll-fn [retry-count]
               (tracef "async-poll-for-update!")
               (when (have-handle?)
                 (let [retry-fn
                       (fn [] ; Backoff then recur
                         (when (have-handle?)
                           (let [retry-count* (inc retry-count)
                                 backoff-ms (backoff-ms-fn retry-count*)
                                 udt-next-reconnect (+ (enc/now-udt) backoff-ms)]
                             (warnf "Chsk is closed: will try reconnect attempt (%s) in %s ms"
                               retry-count* backoff-ms)
                             (.setTimeout goog/global
                               (fn [] (poll-fn retry-count*))
                               backoff-ms)
                             (swap-chsk-state! chsk
                               #(assoc % :udt-next-reconnect udt-next-reconnect)))))]

                   (reset! curr-xhr_
                     (ajax-lite url
                       (merge ajax-opts
                         {:method     :get ; :timeout-ms timeout-ms
                          :timeout-ms (or (:timeout-ms ajax-opts)
                                        default-client-side-ajax-timeout-ms)
                          :resp-type  :text ; Prefer to do our own pstr reading
                          :params
                          (merge
                            ;; Note that user params here are actually POST
                            ;; params for convenience. Contrast: WebSocket
                            ;; params sent as query params since there's no
                            ;; other choice there.
                            params ; 1st (don't clobber impl.):

                            {:udt       (enc/now-udt) ; Force uncached resp
                             :client-id client-id}

                            ;; A truthy :handshake? param will prompt server to
                            ;; reply immediately with a handshake response,
                            ;; letting us confirm that our client<->server comms
                            ;; are working:
                            (when-not (:open? @state_) {:handshake? true}))

                          :headers
                          (merge
                            (:headers ajax-opts) ; 1st (don't clobber impl.)
                            {:X-CSRF-Token (:csrf-token @state_)})})

                       (fn ajax-cb [{:keys [?error ?content]}]
                         (if ?error
                           (cond
                             (= ?error :timeout) (poll-fn 0)
                             ;; (= ?error :abort) ; Abort => intentional, not an error
                             :else
                             (do
                               (swap-chsk-state! chsk
                                 #(chsk-state->closed % :unexpected))
                               (retry-fn)))

                           ;; The Ajax long-poller is used only for events, never cbs:
                           (let [content ?content
                                 ppstr content
                                 [clj] (unpack packer ppstr)
                                 handshake? (handshake? clj)]

                             (when handshake?
                               (receive-handshake! :ajax chsk clj))

                             (swap-chsk-state! chsk #(assoc % :open? true))
                             (poll-fn 0) ; Repoll asap

                             (when-not handshake?
                               (or
                                 (when (= clj :chsk/timeout)
                                   (when @debug-mode?_
                                     (receive-buffered-evs! chs [[:debug/timeout]]))
                                   :noop)

                                 (let [buffered-evs clj] ; An application reply
                                   (receive-buffered-evs! chs buffered-evs))))))))))))]

         (poll-fn 0)
         chsk))))

#?(:cljs
   (defn- new-ChAjaxSocket [opts csrf-token]
     (map->ChAjaxSocket
       (merge
         {:state_           (atom {:type :ajax :open? false :ever-opened? false :csrf-token csrf-token})
          :instance-handle_ (atom nil)
          :ever-opened?_    (atom false)
          :curr-xhr_        (atom nil)}
         opts))))

#?(:cljs
   (defrecord ChAutoSocket
       ;; Dynamic WebSocket/Ajax IChSocket implementation
       ;; Wraps a swappable ChWebSocket/ChAjaxSocket

       [ws-chsk-opts ajax-chsk-opts state_
        impl_ ; ChWebSocket or ChAjaxSocket
        ]

     IChSocket
     (-chsk-disconnect! [chsk reason]
       (when-let [impl @impl_]
         (-chsk-disconnect! impl reason)))

     ;; Possibly reset impl type:
     (-chsk-reconnect! [chsk]
       (when-let [impl @impl_]
         (-chsk-disconnect! impl :requested-reconnect)
         (-chsk-connect!    chsk)))

     (-chsk-send! [chsk ev opts]
       (if-let [impl @impl_]
         (-chsk-send! impl ev opts)
         (let [{?cb :cb} opts
               ?cb-fn (cb-chan-as-fn ?cb ev)]
           (chsk-send->closed! ?cb-fn))))

     (-chsk-connect! [chsk]
       ;; Starting with a simple downgrade-only strategy here as a proof of concept
       ;; TODO Later consider smarter downgrade or downgrade+upgrade strategies?
       (let [ajax-chsk-opts (assoc ajax-chsk-opts :state_ state_)
             ws-chsk-opts (assoc   ws-chsk-opts :state_ state_)

             ajax-conn!
             (fn []
               ;; Remove :auto->:ajax downgrade watch
               (remove-watch state_ :chsk/auto-ajax-downgrade)
               (-chsk-connect! (new-ChAjaxSocket ajax-chsk-opts (:csrf-token @state_))))

             ws-conn!
             (fn []
               ;; Configure :auto->:ajax downgrade watch
               (let [downgraded?_ (atom false)]
                 (add-watch state_ :chsk/auto-ajax-downgrade
                   (fn [_ _ old-state new-state]
                     (when-let [impl @impl_]
                       (when-let [ever-opened?_ (:ever-opened?_ impl)]
                         (when-not @ever-opened?_
                           (when (:last-ws-error new-state)
                             (when (compare-and-set! downgraded?_ false true)
                               (warnf "Permanently downgrading :auto chsk -> :ajax")
                               (-chsk-disconnect! impl :downgrading-ws-to-ajax)
                               (reset! impl_ (ajax-conn!))))))))))

               (-chsk-connect! (new-ChWebSocket ws-chsk-opts (:csrf-token @state_))))]

         (reset! impl_ (or (ws-conn!) (ajax-conn!)))
         chsk))))

#?(:cljs
   (defn- new-ChAutoSocket [opts csrf-token]
     (map->ChAutoSocket
       (merge
         {:state_ (atom {:type :auto :open? false :ever-opened? false :csrf-token csrf-token})
          :impl_  (atom nil)}
         opts))))

   (defn- get-chsk-url [protocol host path type]
     (let [protocol (case protocol :http "http:" :https "https:" protocol)
           protocol (have [:el #{"http:" "https:"}] protocol)
           protocol (case type
                      :ajax     protocol
                      :ws (case protocol "https:" "wss:" "http:" "ws:"))]
       (str protocol "//" (enc/path host path))))

   (defn make-channel-socket-client!
     "Returns nil on failure, or a map with keys:
       :ch-recv ; core.async channel to receive `event-msg`s (internal or from
                ; clients). May `put!` (inject) arbitrary `event`s to this channel.
       :send-fn ; (fn [event & [?timeout-ms ?cb-fn]]) for client>server send.
       :state   ; Watchable, read-only (atom {:type _ :open? _ :uid _ :csrf-token _}).
       :chsk    ; IChSocket implementer. You can usu. ignore this.

     Common options:
       :type           ; e/o #{:auto :ws :ajax}. You'll usually want the default (:auto).
       :protocol       ; Server protocol, e/o #{:http :https}.
       :host           ; Server host (defaults to current page's host).
       :port           ; Server port (defaults to current page's port).
       :params         ; Map of any params to incl. in chsk Ring requests (handy
                       ; for application-level auth, etc.).
       :headers        ; Map of additional headers to include in the initiating request
                       ; (currently only for Java clients).
       :packer         ; :edn (default), or an IPacker implementation.
       :ajax-opts      ; Base opts map provided to `taoensso.encore/ajax-lite`.
       :wrap-recv-evs? ; Should events from server be wrapped in [:chsk/recv _]?
       :ws-kalive-ms   ; Ping to keep a WebSocket conn alive if no activity
                       ; w/in given msecs. Should be different to server's :ws-kalive-ms."

     [path ?csrf-token &
      [{:keys [type protocol host port params headers recv-buf-or-n packer ws-kalive-ms
               client-id ajax-opts wrap-recv-evs? backoff-ms-fn]
        :as   opts
        :or   {type           :auto
               recv-buf-or-n  (async/sliding-buffer 2048) ; Mostly for buffered-evs
               packer         :edn
               client-id      (or (:client-uuid opts) ; Backwards compatibility
                                  (enc/uuid-str))
               wrap-recv-evs? true
               backoff-ms-fn  enc/exp-backoff
               ws-kalive-ms   (enc/ms :secs 20)}}

       _deprecated-more-opts]]

     (have? [:in #{:ajax :ws :auto}] type)
     (have? enc/nblank-str? client-id)

     (when (not (nil? _deprecated-more-opts)) (warnf "`make-channel-socket-client!` fn signature CHANGED with Sente v0.10.0."))
     (when (contains? opts :lp-timeout) (warnf ":lp-timeout opt has CHANGED; please use :lp-timout-ms."))

     (when (not (or
                  (fn? ?csrf-token)
                  (and
                    (string? ?csrf-token)
                    (not (str/blank? ?csrf-token)))))
       (warnf "WARNING: no CSRF token provided. Connections will FAIL if server-side CSRF check is enabled (as it is by default)."))

     (let [packer (coerce-packer packer)

           [ws-url ajax-url]
           (let [;; Not available with React Native, etc.
                 ;; Must always provide explicit path for Java client.
                 win-loc  #?(:clj nil :cljs (enc/get-win-loc))
                 path     (have (or path (:pathname win-loc)))]

             (if-let [f (:chsk-url-fn opts)] ; Deprecated
               [(f path win-loc :ws)
                (f path win-loc :ajax)]

               (let [protocol (or protocol (:protocol win-loc) :http)
                     host
                     (if host
                       (if port (str host ":" port) host)
                       (if port
                         (str (:hostname win-loc) ":" port)
                         (do  (:host     win-loc))))]

                 [(get-chsk-url protocol host path :ws)
                  (get-chsk-url protocol host path :ajax)])))

           private-chs
           {:internal (chan (async/sliding-buffer 128))
            :state    (chan (async/sliding-buffer 10))
            :<server
            (let [;; Nb must be >= max expected buffered-evs size:
                  buf (async/sliding-buffer 512)]
              (if wrap-recv-evs?
                (chan buf (map (fn [ev] [:chsk/recv ev])))
                (chan buf)))}

           common-chsk-opts
           {:client-id    client-id
            :chs          private-chs
            :params       params
            :headers      headers
            :packer       packer
            :ws-kalive-ms ws-kalive-ms}

           ws-chsk-opts
           (merge common-chsk-opts
             {:url           ws-url
              :backoff-ms-fn backoff-ms-fn})

           ajax-chsk-opts
           (merge common-chsk-opts
             {:url           ajax-url
              :ajax-opts     ajax-opts
              :backoff-ms-fn backoff-ms-fn})

           auto-chsk-opts
           {:ws-chsk-opts   ws-chsk-opts
            :ajax-chsk-opts ajax-chsk-opts}

           ?chsk
           (-chsk-connect!
             (case type
               :ws      (new-ChWebSocket    ws-chsk-opts ?csrf-token)
               :ajax
               #?(:cljs (new-ChAjaxSocket ajax-chsk-opts ?csrf-token)
                  :clj  (throw (UnsupportedOperationException.
                                 "Only :ws channel socket type supported for clj")))
               :auto
               #?(:cljs (new-ChAutoSocket auto-chsk-opts ?csrf-token)
                  :clj  (throw (UnsupportedOperationException.
                                 "Only :ws channel socket type supported for clj")))))]

       (if-let [chsk ?chsk]
         (let [chsk-state_ (:state_ chsk)
               internal-ch (:internal private-chs)
               send-fn (partial chsk-send! chsk)
               ev-ch
               (async/merge
                 [(:internal private-chs)
                  (:state    private-chs)
                  (:<server  private-chs)]
                 recv-buf-or-n)

               ev-msg-ch
               (async/chan 1
                 (map
                   (fn [ev]
                     (let [[ev-id ev-?data :as ev] (as-event ev)]
                       {;; Allow client to inject into router for handler:
                        :ch-recv internal-ch
                        :send-fn send-fn
                        :state   chsk-state_
                        :event   ev
                        :id      ev-id
                        :?data   ev-?data}))))]

           (async/pipe ev-ch ev-msg-ch)

           {:chsk    chsk
            :ch-recv ev-msg-ch
            :send-fn send-fn
            :state   (:state_ chsk)})

         (warnf "Failed to create channel socket"))))

;;;; Event-msg routers (handler loops)

(defn- -start-chsk-router!
  [server? ch-recv event-msg-handler opts]
  (let [{:keys [trace-evs? error-handler simple-auto-threading?]} opts
        ch-ctrl (chan)

        execute1
        #?(:cljs (fn [f] (f))
           :clj
           (if simple-auto-threading?
             (fn [f] (future-call f))
             (fn [f] (f))))]

    (go-loop []
      (let [[v p] (async/alts! [ch-recv ch-ctrl])
            stop? (or (= p ch-ctrl) (nil? v))]

        (when-not stop?
          (let [{:as event-msg :keys [event]} v]

            (execute1
              (fn []
                (enc/catching
                  (do
                    (when trace-evs? (tracef "Pre-handler event: %s" event))
                    (event-msg-handler
                      (if server?
                        (have! server-event-msg? event-msg)
                        (have! client-event-msg? event-msg))))
                  e1
                  (enc/catching
                    (if-let [eh error-handler]
                      (error-handler e1 event-msg)
                      (errorf e1 "Chsk router `event-msg-handler` error: %s" event))
                    e2 (errorf e2 "Chsk router `error-handler` error: %s"     event)))))

            (recur)))))

    (fn stop! [] (async/close! ch-ctrl))))

(defn start-server-chsk-router!
  "Creates a simple go-loop to call `(event-msg-handler <server-event-msg>)`
  and log any errors. Returns a `(fn stop! [])`. Note that advanced users may
  prefer to just write their own loop against `ch-recv`.

  Nb performance note: since your `event-msg-handler` fn will be executed
  within a simple go block, you'll want this fn to be ~non-blocking
  (you'll especially want to avoid blocking IO) to avoid starving the
  core.async thread pool under load. To avoid blocking, you can use futures,
  agents, core.async, etc. as appropriate.

  Or for simple automatic future-based threading of every request, enable
  the `:simple-auto-threading?` opt (disabled by default)."
  [ch-recv event-msg-handler &
   [{:as opts :keys [trace-evs? error-handler simple-auto-threading?]}]]
  (-start-chsk-router! :server ch-recv event-msg-handler opts))

(defn start-client-chsk-router!
  "Creates a simple go-loop to call `(event-msg-handler <server-event-msg>)`
  and log any errors. Returns a `(fn stop! [])`. Note that advanced users may
  prefer to just write their own loop against `ch-recv`.

  Nb performance note: since your `event-msg-handler` fn will be executed
  within a simple go block, you'll want this fn to be ~non-blocking
  (you'll especially want to avoid blocking IO) to avoid starving the
  core.async thread pool under load. To avoid blocking, you can use futures,
  agents, core.async, etc. as appropriate."
  [ch-recv event-msg-handler &
   [{:as opts :keys [trace-evs? error-handler]}]]
  (-start-chsk-router! (not :server) ch-recv event-msg-handler opts))

;;;; Platform aliases

(def event-msg? #?(:clj server-event-msg? :cljs client-event-msg?))

(def make-channel-socket!
  "Platform-specific alias for `make-channel-socket-server!` or
  `make-channel-socket-client!`. Please see the appropriate aliased fn
   docstring for details."
  #?(:clj  make-channel-socket-server!
     :cljs make-channel-socket-client!))

(def start-chsk-router!
  "Platform-specific alias for `start-server-chsk-router!` or
  `start-client-chsk-router!`. Please see the appropriate aliased fn
  docstring for details."
  #?(:clj  start-server-chsk-router!
     :cljs start-client-chsk-router!))

;;;; Deprecated

(enc/deprecated
  #?(:clj
     (defn start-chsk-router-loop!
       "DEPRECATED: Please use `start-chsk-router!` instead"
       [event-msg-handler ch-recv]
       (start-server-chsk-router! ch-recv
         ;; Old handler form: (fn [ev-msg ch-recv])
         (fn [ev-msg] (event-msg-handler ev-msg (:ch-recv ev-msg))))))

  #?(:cljs
     (defn start-chsk-router-loop!
       "DEPRECATED: Please use `start-chsk-router!` instead"
       [event-handler ch-recv]
       (start-client-chsk-router! ch-recv
         ;; Old handler form: (fn [ev ch-recv])
         (fn [ev-msg] (event-handler (:event ev-msg) (:ch-recv ev-msg))))))

  (def set-logging-level! "DEPRECATED. Please use `timbre/set-level!` instead" timbre/set-level!)

  #?(:cljs (def ajax-call "DEPRECATED: Please use `ajax-lite` instead" enc/ajax-lite))
  #?(:cljs
     (def default-chsk-url-fn "DEPRECATED"
       (fn [path {:as location :keys [protocol host pathname]} websocket?]
         (let [protocol
               (if websocket?
                 (if (= protocol "https:") "wss:" "ws:")
                 protocol)]
           (str protocol "//" host (or path pathname)))))))
