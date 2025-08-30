(ns taoensso.sente
  "Channel sockets for Clojure/Script.

      Protocol  | client>server | client>server ?+ ack/reply | server>user push
    * WebSockets:       ✓              [1]                           ✓
    * Ajax:            [2]              ✓                           [3]

    [1] Emulate with cb-uuid wrapping
    [2] Emulate with dummy-cb wrapping
    [3] Emulate with long-polling

  Terminology:
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
    * packed    - Arbitrary Clojure value serialized for client<->server comms
    * udt       - Unix timestamp (datetime long)

  Special messages:
    * Callback wrapping: [<clj> <?cb-uuid>] for [1], [2]
    * Callback replies: :chsk/closed, :chsk/timeout, :chsk/error

    * Client-side events:
        [:chsk/ws-ping] ; ws-ping from server
        [:chsk/handshake [<?uid> nil <?handshake-data> <first-handshake?>]]
        [:chsk/state     [<old-state-map> <new-state-map> <open-change?>]]
        [:chsk/recv      <ev-as-pushed-from-server>] ; Server>user push

    * Server-side events:
        [:chsk/ws-ping] ; ws-ping from client
        [:chsk/ws-pong] ; ws-pong from client
        [:chsk/uidport-open  <uid>]
        [:chsk/uidport-close <uid>]
        [:chsk/bad-event     <event>]

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
                            #{nil :clean :unexpected :requested-disconnect
                              :requested-reconnect :downgrading-ws-to-ajax
                              :ws-ping-timeout :ws-error}
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
      this approach to modifying handlers (better portability)."

  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [clojure.string         :as str]
   [clojure.core.async     :as async]
   [taoensso.encore        :as enc   :refer [swap-in! reset-in! swapped]]
   [taoensso.encore.timers :as timers]
   [taoensso.truss         :as truss]
   [taoensso.trove         :as trove]
   [taoensso.sente.interfaces :as i])

  #?(:cljs
     (:require-macros
      [taoensso.sente :as sente-macros :refer [elide-require]])))

(enc/assert-min-encore-version [3 154 0])
(def sente-version
  "Useful for identifying client/server mismatch"
  [1 21 0])

#?(:cljs (def ^:private node-target? (= *target* "nodejs")))

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
        (not (keyword?  ev-id)) {:wrong-id-type   (expected :keyword            ev-id)}
        (not (namespace ev-id)) {:unnamespaced-id (expected :namespaced-keyword ev-id)}
        :else nil))))

(defn assert-event
  "Returns given argument if it is a valid [ev-id ?ev-data] form. Otherwise
  throws a validation exception."
  [x]
  (when-let [errs (validate-event x)]
    (truss/ex-info! "Invalid event" {:given x :errors errs})))

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
    (if (server-event-msg? ev-msg*)
      (async/put! ch-recv  ev-msg*)
      (trove/log! {:level :warn, :id :sente.server/bad-event-msg, :data {:ev-msg ev-msg}}) ; Log and drop
      )))

;; Note that cb replies need _not_ be `event` form!
(defn cb-error?   [arb-reply-clj] (case arb-reply-clj (:chsk/closed :chsk/timeout :chsk/error) true  false))
(defn cb-success? [arb-reply-clj] (case arb-reply-clj (:chsk/closed :chsk/timeout :chsk/error) false true))

;;;; Packing (see `i/IPacker2`)

(def ^:no-doc edn-packer
  "Basic EDN-based packer implementation."
  (reify i/IPacker2
    (pack [_ ws? clj cb-fn]
      (cb-fn
        (truss/try*
          (do           {:value (enc/pr-edn clj)})
          (catch :all t {:error t}))))

    (unpack [_ ws? packed cb-fn]
      (cb-fn
        (truss/try*
          (do           {:value (enc/read-edn packed)})
          (catch :all t {:error t}))))))

(defn- coerce-packer [x] (if (= x :edn) edn-packer (truss/have [:satisfies? i/IPacker2] x)))

(defn- on-packed [packer ws?  clj ?cb-uuid cb-fn]
  (i/pack         packer ws? [clj ?cb-uuid] ; Note wrapping to add ?cb-uuid
    (fn [{error :error, packed :value}]
      (if error
        (trove/log!
          {:level :error, :id :sente.packer/pack-failure, :error error,
           :data {:ws? ws?, :given {:value clj, :type (type clj)}}})
        (cb-fn packed)))))

(defn- on-unpacked [packer ws? packed cb-fn]
  (i/unpack         packer ws? packed
    (fn [{error :error, clj :value}]
      (if error
        (trove/log!
          {:level :error, :id :sente.packer/unpack-failure, :error error,
           :data {:ws? ws?, :given {:value packed, :type (type packed)}}})
        (cb-fn clj)))))

;;;; Server API

(def ^:private next-idx! (enc/counter))

(declare
  ^:private send-buffered-server-evs>clients!
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

#?(:clj
   (defn- stream->ba [x]
     (if (instance? java.io.InputStream x)
       (with-open [in x, out (java.io.ByteArrayOutputStream.)]
         (clojure.java.io/copy in out)
         (.toByteArray            out))
       x)))

(defn make-channel-socket-server!
  "Takes a web server adapter[1] and returns a map with keys:

    :ch-recv ; core.async channel to receive `event-msg`s (internal or from clients).
    :send-fn                     ; (fn [user-id ev] for server>user push.
    :ajax-post-fn                ; Ring handler for CSRF-POST + chsk URL.
    :ajax-get-or-ws-handshake-fn ; Ring handler for Ring GET  + chsk URL.
    :connected-uids ; Watchable, read-only (atom {:ws #{_} :ajax #{_} :any #{_}}).

  Security options:

    :allowed-origins   ; e.g. #{\"http://site.com\" ...}, defaults to :all. ; Alpha

    :csrf-token-fn     ; ?(fn [ring-req]) -> CSRF-token for Ajax POSTs and WS handshake.
                       ; nil fn or `:sente/skip-CSRF-check` return val => CSRF check will be
                       ; SKIPPED (can pose a *CSRF SECURITY RISK* for website use cases, so
                       ; please ONLY do this check if you're very sure you understand the
                       ; security implications!).

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

    :user-id-fn         ; (fn [ring-req]) -> unique user-id for server>user push.
    :handshake-data-fn  ; (fn [ring-req]) -> arb user data to append to handshake evs.
    :lp-timeout-ms      ; Timeout (repoll) long-polling Ajax conns after given msecs.
    :send-buf-ms-ajax   ; [2]
    :send-buf-ms-ws     ; [2]
    :packer             ; :edn (default), or an IPacker implementation.

    :ws-kalive-ms       ; Max msecs to allow WebSocket inactivity before server sends ping to client.
    :ws-ping-timeout-ms ; Max msecs to wait for ws-kalive ping response before concluding conn is broken.

    ;; When a connection is closed, Sente waits a little for possible reconnection before
    ;; actually marking the connection as closed. This facilitates Ajax long-polling,
    ;; server->client buffering, and helps to reduce event noise from spotty connections.
    :ms-allow-reconnect-before-close-ws   ; Msecs to wait for WebSocket conns (default: 2500)
    :ms-allow-reconnect-before-close-ajax ; Msecs to wait for Ajax      conns (default: 5000)

  [1] e.g. `(taoensso.sente.server-adapters.http-kit/get-sch-adapter)` or
           `(taoensso.sente.server-adapters.immutant/get-sch-adapter)`.
      You must have the necessary web-server dependency in your project.clj and
      the necessary entry in your namespace's `ns` form.

  [2] Optimization to allow transparent batching of rapidly-triggered
      server>user pushes. This is esp. important for Ajax clients which use a
      (slow) reconnecting poller. Actual event dispatch may occur <= given ms
      after send call (larger values => larger batch windows)."

  [web-server-ch-adapter
   & [{:keys [recv-buf-or-n ws-kalive-ms lp-timeout-ms ws-ping-timeout-ms
              send-buf-ms-ajax send-buf-ms-ws
              user-id-fn bad-csrf-fn bad-origin-fn csrf-token-fn
              handshake-data-fn packer allowed-origins
              authorized?-fn unauthorized-fn ?unauthorized-fn

              ms-allow-reconnect-before-close-ws
              ms-allow-reconnect-before-close-ajax]

       :or   {recv-buf-or-n      (async/sliding-buffer 1000)
              ws-kalive-ms       (enc/ms :secs 25) ; < Heroku 55s timeout
              lp-timeout-ms      (enc/ms :secs 20) ; < Heroku 30s timeout
              ws-ping-timeout-ms (enc/ms :secs 5)

              send-buf-ms-ajax 100
              send-buf-ms-ws   30

              user-id-fn      (fn [ ring-req] (get-in ring-req [:session :uid]))
              bad-csrf-fn     (fn [_ring-req] {:status 403 :body "Bad CSRF token"})
              bad-origin-fn   (fn [_ring-req] {:status 403 :body "Unauthorized origin"})
              unauthorized-fn (fn [_ring-req] {:status 401 :body "Unauthorized request"})

              ms-allow-reconnect-before-close-ws   2500
              ms-allow-reconnect-before-close-ajax 5000

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

  (truss/have? enc/pos-int? send-buf-ms-ajax send-buf-ms-ws)
  (truss/have? #(satisfies? i/IServerChanAdapter %) web-server-ch-adapter)

  (let [max-ms default-client-side-ajax-timeout-ms]
    (when (>= lp-timeout-ms max-ms)
      (truss/ex-info!
        (str ":lp-timeout-ms must be < " max-ms)
        {:lp-timeout-ms lp-timeout-ms
         :default-client-side-ajax-timeout-ms max-ms})))

  (let [allowed-origins (truss/have [:or set? #{:all}] allowed-origins)
        ws-kalive-ms    (when ws-kalive-ms (quot ws-kalive-ms 2)) ; Ref. #455
        packer  (coerce-packer packer)
        ch-recv (async/chan recv-buf-or-n)

        user-id-fn
        (fn [ring-req client-id]
          ;; Allow uid to depend (in part or whole) on client-id. Be cautious
          ;; of security implications.
          (or (user-id-fn (assoc ring-req :client-id client-id)) :sente/nil-uid))

        conns_          (atom {:ws  {} :ajax  {}}) ; {<uid> {<client-id> [<?sch> <udt-last-activity> <conn-id>]}}
        send-buffers_   (atom {:ws  {} :ajax  {}}) ; {<uid> [<buffered-evs> <#{ev-uuids}>]}
        connected-uids_ (atom {:ws #{} :ajax #{} :any #{}}) ; Public

        connect-uid!?
        (fn [conn-type uid] {:pre [(truss/have? uid)]}
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

        maybe-disconnect-uid!?
        (fn [uid] {:pre [(truss/have? uid)]}
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
          (let [uid (if (= user-id :sente/all-users-without-uid) :sente/nil-uid user-id)
                _   (trove/log! {:level :trace, :id :sente.server/send-to-uid, :data {:uid uid, :ev ev}})
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
                      (truss/have? vector? buffered-evs)
                      (truss/have? set?    ev-uuids)

                      (on-packed packer (= conn-type :ws) buffered-evs nil
                        (fn [packed]
                          (send-buffered-server-evs>clients! conn-type
                            conns_ uid packed (count buffered-evs)))))))]

            (if (= ev [:chsk/close]) ; Currently undocumented
              (do
                (trove/log! {:level :debug, :id :sente.server/close-chsk-ev, :data {:uid uid}})
                (when flush?
                  (flush-buffer! :ws)
                  (flush-buffer! :ajax))

                (doseq [[?sch _udt] (vals (get-in @conns_ [:ws uid]))]
                  (when-let [sch ?sch] (i/sch-close! sch)))

                (doseq [[?sch _udt] (vals (get-in @conns_ [:ajax uid]))]
                  (when-let [sch ?sch] (i/sch-close! sch))))

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
                  (do
                    (timers/call-after send-buf-ms-ws   (fn [] (flush-buffer! :ws)))
                    (timers/call-after send-buf-ms-ajax (fn [] (flush-buffer! :ajax))))))))

          ;; Server-side send is async so nothing useful to return (currently
          ;; undefined):
          nil)

        bad-csrf?
        (fn [ring-req]
          (if (nil? csrf-token-fn)
            false ; Pass (skip check)
            (if-let [reference-csrf-token (csrf-token-fn ring-req)]
              (if (= reference-csrf-token :sente/skip-CSRF-check)
                false ; Pass (skip check)
                (let [csrf-token-from-client
                      (or
                        (get-in ring-req [:params    :csrf-token])
                        (get-in ring-req [:headers "x-csrf-token"])
                        (get-in ring-req [:headers "x-xsrf-token"]))]

                  (not
                    (enc/const-str=
                      reference-csrf-token
                      csrf-token-from-client))))

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

    {:ch-recv         ch-recv
     :send-fn         send-fn
     :connected-uids_ connected-uids_
     :connected-uids  connected-uids_ ; For back compatibility
     :private         {:conns_        conns_
                       :send-buffers_ send-buffers_}

     ;; Does not participate in `conns_` (has specific req->resp)
     :ajax-post-fn
     (fn ring-handler
       ([ring-req] (ring-handler ring-req nil nil))
       ([ring-req ?ring-async-resp-fn ?ring-async-raise-fn]
        (enc/cond
          :if-let [resp (possible-rejection-resp ring-req)] resp
          :else
          (i/ring-req->server-ch-resp web-server-ch-adapter ring-req
            {:ring-async-resp-fn  ?ring-async-resp-fn
             :ring-async-raise-fn ?ring-async-raise-fn
             :on-open
             (fn [server-ch websocket?]
               (enc/cond
                 :do (assert (not websocket?))

                 :let
                 [params    (get ring-req :params)
                  client-id (get params   :client-id)
                  binary?   (get params   :binary?)
                  packed
                  (if-not binary?
                    (do        (get params   :ppstr)) ; Expect form-urlencoded
                    (let [body (get ring-req :body)]  ; Expect `InputStream` bytes
                      #?(:clj (stream->ba body), :cljs body)))]

                 :do
                 (on-unpacked packer websocket? packed
                   (fn [[ev-clj has-cb?]]
                     (let [replied?_ (atom false)
                           reply-fn
                           (fn [arb-reply-clj]
                             (when (compare-and-set! replied?_ false true)
                               (trove/log!
                                 {:level :debug,
                                  :id    :sente.server/send-reply
                                  :data  {:uid (user-id-fn ring-req client-id)
                                          :cid                      client-id
                                          :ws?   websocket?
                                          :reply arb-reply-clj}})

                               (when (i/sch-open? server-ch)
                                 (on-packed packer websocket? arb-reply-clj nil
                                   (fn [packed] (i/sch-send! server-ch websocket? packed)))
                                 true)))]

                       (put-server-event-msg>ch-recv! ch-recv
                         (enc/merge ev-msg-const
                           {:ring-req  ring-req
                            :client-id client-id ; For user's convenience (not used by Sente)
                            :event     ev-clj
                            :uid       (user-id-fn ring-req client-id)
                            :?reply-fn (when has-cb? reply-fn)}))

                       (if has-cb?
                         (when-let [ms lp-timeout-ms]
                           (timers/call-after ms (fn [] (reply-fn :chsk/timeout))))
                         (do                            (reply-fn :chsk/dummy-cb-200))))))

                 :then nil))}))))

     ;; Ajax handshake/poll, or WebSocket handshake
     :ajax-get-or-ws-handshake-fn
     (fn ring-handler
       ([ring-req] (ring-handler ring-req nil nil))
       ([ring-req ?ring-async-resp-fn ?ring-async-raise-fn]
        (let [;; ?ws-key  (get-in ring-req [:headers "sec-websocket-key"])
              conn-id     (enc/uuid-str 6) ; 1 per ws/ajax rreq, equiv to server-ch identity
              params      (get ring-req :params)
              client-id   (get params   :client-id)
              uid         (user-id-fn ring-req client-id)]

          (enc/cond
            (str/blank? client-id)
            (let [error
                  (truss/ex-info
                    "Client's Ring request doesn't have a client id. Does your server have the necessary keyword Ring middleware (`wrap-params` & `wrap-keyword-params`)?"
                    {:ring-req ring-req, :uid uid, :cid client-id})]

              (trove/log!
                {:level :error, :id :sente.server/no-client-id, :error error,
                 :data {:uid uid, :client-id client-id}})

              (throw error))

            :if-let [resp (possible-rejection-resp ring-req)] resp

            :else
            (let [receive-event-msg! ; Partial
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
                    (trove/log!
                      {:level :debug, :id :sente.server/send-handshake,
                       :data {:uid uid, :cid client-id, :ws? websocket?}})

                    (on-packed packer websocket?
                      [:chsk/handshake [uid nil (handshake-data-fn ring-req)]] nil
                      (fn [packed] (i/sch-send! server-ch websocket? packed))))

                  on-error
                  (fn [server-ch websocket? error]
                    (trove/log!
                      {:level :error, :id :sente.server/error, :error error,
                       :data {:uid uid, :cid client-id, :ws? websocket?}}))

                  on-msg
                  (fn [server-ch websocket? packed]
                    (assert websocket?)
                    (swap-in! conns_ [:ws uid client-id]
                      (fn [[?sch _udt conn-id]]
                        (when conn-id [?sch (enc/now-udt) conn-id])))

                    (on-unpacked packer websocket? packed
                      (fn   [[ev-clj ?cb-uuid]]
                        (case ev-clj
                          [:chsk/ws-pong] (receive-event-msg! ev-clj nil)
                          [:chsk/ws-ping]
                          (do
                            ;; Auto reply to ping
                            (when-let [cb-uuid ?cb-uuid]
                              (trove/log!
                                {:level :debug, :id :sente.server/send-ws-pong,
                                 :data {:uid uid, :cid client-id}})

                              (on-packed packer websocket? "pong" cb-uuid
                                (fn [packed] (i/sch-send! server-ch websocket? packed))))

                            (receive-event-msg! ev-clj nil))

                          ;; else
                          (receive-event-msg! ev-clj
                            (when-let [cb-uuid ?cb-uuid]
                              (fn reply-fn [arb-reply-clj]
                                (when (i/sch-open? server-ch)
                                  (trove/log!
                                    {:level :debug, :id :sente.server/send-reply,
                                     :data {:uid uid, :cid client-id, :ws? websocket?, :reply arb-reply-clj}})

                                  (on-packed packer websocket? arb-reply-clj cb-uuid
                                    (fn [packed] (i/sch-send! server-ch websocket? packed)))
                                  true))))))))

                  on-close
                  (fn [server-ch websocket? _status]
                    ;; - We rely on `on-close` to trigger for *every* sch.
                    ;; - May be called *more* than once for a given sch.
                    ;; - `status` type varies with underlying web server.
                    (let [conn-type (if websocket? :ws :ajax)
                          active-conn-closed?
                          (swap-in! conns_ [conn-type uid client-id]
                            (fn [[?sch _udt conn-id*]]
                              (if (= conn-id conn-id*)
                                (swapped [nil (enc/now-udt) conn-id] true)
                                (swapped :swap/abort                 false))))]

                      ;; Inactive => a connection closed that's not currently in conns_

                      (trove/log!
                        {:level :debug, :id :sente.server/close,
                         :data {:uid uid, :cid client-id, :ws? websocket?, :was-active? active-conn-closed?}})

                      (when active-conn-closed?
                        ;; Allow some time for possible reconnects (repoll,
                        ;; sole window refresh, etc.) before regarding close
                        ;; as non-transient "disconnect"
                        (let [ms-timeout
                              (if websocket?
                                ms-allow-reconnect-before-close-ws
                                ms-allow-reconnect-before-close-ajax)]

                          (timers/call-after ms-timeout
                            (fn []
                              (let [[active-conn-disconnected? ?conn-entry]
                                    (swap-in! conns_ [conn-type uid client-id]
                                      (fn [[_?sch _udt conn-id* :as ?conn-entry]]
                                        (if (= conn-id conn-id*)
                                          (swapped :swap/dissoc [true  ?conn-entry])
                                          (swapped :swap/abort  [false ?conn-entry]))))]

                                (trove/log!
                                  {:level (if websocket? :debug :trace),
                                   :id :sente.server/close-timeout,
                                   :data
                                   (conj
                                     {:uid uid, :cid client-id, :ws? websocket?}
                                     (if active-conn-disconnected?
                                       {:disconnected? true}
                                       {:disconnected? false, :?conn-entry ?conn-entry}))})

                                (when active-conn-disconnected?

                                  ;; Potentially remove uid's entire entry
                                  (swap-in! conns_ [conn-type uid]
                                    (fn [m-clients]
                                      (if (empty? m-clients)
                                        :swap/dissoc
                                        :swap/abort)))

                                  (when (maybe-disconnect-uid!? uid)
                                    (trove/log!
                                      {:level :debug,
                                       :id :sente.server/uidport-close,
                                       :data {:uid uid, :cid client-id, :ws? websocket?}})
                                    (receive-event-msg! [:chsk/uidport-close uid]))))))))))

                  on-open
                  (fn [server-ch websocket?]
                    (if websocket?

                      ;; WebSocket handshake
                      (do
                        (trove/log! {:level :debug, :id :sente.server/ws-open, :data {:uid uid, :cid client-id}})
                        (send-handshake! server-ch websocket?)
                        (let [[_ udt-open]
                              (swap-in! conns_ [:ws uid client-id]
                                (fn [_] [server-ch (enc/now-udt) conn-id]))]

                          ;; Server-side loop to detect broken conns, Ref. #230
                          (when ws-kalive-ms
                            (let [loop-fn
                                  (fn loop-fn  [udt-t0 ms-timeout expecting-pong?]
                                    (timers/call-after ms-timeout
                                      (fn []
                                        (let [?conn-entry (get-in @conns_ [:ws uid client-id])
                                              [?sch udt-t1 conn-id*] ?conn-entry

                                              {:keys [recur? udt ms-timeout expecting-pong? force-close?]}
                                              (enc/cond
                                                (nil? ?conn-entry)                            {:recur? false}
                                                (not= conn-id conn-id*)                       {:recur? false}
                                                (when-let [sch ?sch] (not (i/sch-open? sch))) {:recur? false, :force-close? true}

                                                (not= udt-t0 udt-t1) ; Activity in last kalive window
                                                {:recur? true, :udt udt-t1, :ms-timeout ws-kalive-ms, :expecting-pong? false}

                                                :do (trove/log! {:level :debug, :id :sente.server/ws-inactive, :data {:uid uid, :cid client-id}})

                                                expecting-pong?
                                                (do
                                                  ;; Was expecting pong (=> activity) in last kalive window
                                                  (i/sch-close! server-ch)
                                                  {:recur? false})

                                                (i/sch-open? server-ch)
                                                (do
                                                  ;; If conn has gone bad, attempting to send a ping will usu.
                                                  ;; immediately trigger the conn's `:on-close`, i.e. shouldn't
                                                  ;; usually need to wait for a missed pong
                                                  (on-packed packer websocket? :chsk/ws-ping nil
                                                    (fn [packed] (i/sch-send! server-ch websocket? packed)))

                                                  (if ws-ping-timeout-ms
                                                    {:recur? true, :udt udt-t1, :ms-timeout ws-ping-timeout-ms, :expecting-pong? true}
                                                    {:recur? true, :udt udt-t1, :ms-timeout ws-kalive-ms,       :expecting-pong? false}))

                                                :else {:recur? false, :force-close? true})]

                                          (if recur?
                                            (loop-fn udt ms-timeout expecting-pong?)
                                            (do
                                              (trove/log! {:level :debug, :id :sente.server/stop-ws-kalive, :data {:uid uid, :cid client-id}})
                                              (when force-close?
                                                ;; It's rare but possible for a conn's :on-close to fire
                                                ;; *before* a handshake, leaving a closed sch in conns_
                                                (trove/log!
                                                  {:level :debug, :id :sente.server/force-close-ws,
                                                   :data {:uid uid, :cid client-id}})
                                                (on-close server-ch websocket? nil))))))))]

                              (loop-fn udt-open ws-kalive-ms false)))

                          (when (connect-uid!? :ws uid)
                            (trove/log! {:level :debug, :id :sente.server/uidport-open, :data {:uid uid, :cid client-id}})
                            (receive-event-msg! [:chsk/uidport-open uid]))))

                      ;; Ajax handshake/poll
                      (let [send-handshake?
                            (or
                              (get params :handshake?)
                              (nil? (get-in @conns_ [:ajax uid client-id])))]

                        (trove/log!
                          {:level (if send-handshake? :debug :trace),
                           :id    (if send-handshake? :sente.server/send-handshake :sente.server/ajax-poll)
                           :data  {:uid uid, :cid client-id}})

                        (if send-handshake?
                          (do
                            (swap-in! conns_ [:ajax uid client-id] (fn [_] [nil (enc/now-udt) conn-id]))
                            (send-handshake! server-ch websocket?)
                            ;; `server-ch` will close, and client will immediately repoll
                            )

                          (let [[_ udt-open]
                                (swap-in! conns_ [:ajax uid client-id]
                                  (fn [_] [server-ch (enc/now-udt) conn-id]))]

                            (when-let [ms lp-timeout-ms]
                              (timers/call-after ms
                                (fn []
                                  (when-let [[_?sch _udt conn-id*] (get-in @conns_ [:ajax uid client-id])]
                                    (when (= conn-id conn-id*)
                                      (trove/log! {:level :trace, :id :sente.server/ajax-poll-timeout, :data {:uid uid, :cid client-id}})
                                      (on-packed packer websocket? :chsk/timeout nil
                                        (fn [packed] (i/sch-send! server-ch websocket? packed))))))))

                            (when (connect-uid!? :ajax uid)
                              (trove/log! {:level :debug, :id :sente.server/uidport-open, :data {:uid uid, :cid client-id, :ws? websocket?}})
                              (receive-event-msg! [:chsk/uidport-open uid])))))))]

              (i/ring-req->server-ch-resp web-server-ch-adapter ring-req
                {:ring-async-resp-fn  ?ring-async-resp-fn
                 :ring-async-raise-fn ?ring-async-raise-fn
                 :on-open             on-open
                 :on-msg              on-msg
                 :on-close            on-close
                 :on-error            on-error}))))))}))

(def ^:dynamic *simulated-bad-conn-rate*
  "Debugging tool. Proportion ∈ℝ[0,1] of connection activities to sabotage."
  nil)

(defn- simulated-bad-conn? []
  (when-let [sbcr *simulated-bad-conn-rate*]
    (enc/chance sbcr)))

(comment (binding [*simulated-bad-conn-rate* 0.5] (simulated-bad-conn?)))

(defn- send-buffered-server-evs>clients!
  "Actually pushes buffered events (as packed-str) to all uid's conns.
  Allows some time for possible reconnects."
  [conn-type conns_ uid packed-buffered-evs n-buffered-evs]
  (truss/have? [:el #{:ajax :ws}] conn-type)

  (enc/cond
    :let
    [;; Mean max wait time: sum*1.5 = 2790*1.5 = 4.2s
     ms-backoffs [90 180 360 720 720 720] ; => max 1+6 attempts
     websocket?  (= conn-type :ws)
     udt-t0      (enc/now-udt)]

    :when-let [client-ids (keys (get-in @conns_ [conn-type uid]))]
    :let
    [loop-fn
     (fn loop-fn [idx pending]
       (let  [pending
              (reduce
                (fn [pending client-id]
                  (if-let [sent?
                           (when-let [conn-id
                                      (when-let [[?sch _udt conn-id] (get-in @conns_ [conn-type uid client-id])]
                                        (when-let [sch ?sch]
                                          (when-not (simulated-bad-conn?)
                                            (when (i/sch-send! sch websocket? packed-buffered-evs)
                                              conn-id))))]

                             (swap-in! conns_ [conn-type uid client-id]
                               (fn [[?sch udt conn-id*]]
                                 (if (= conn-id conn-id*)
                                   (if websocket?
                                     [?sch (enc/now-udt) conn-id]
                                     [nil  udt           conn-id])
                                   :swap/abort)))
                             true)]

                    (disj pending client-id)
                    (do   pending)))
                pending
                pending)]

         (if-let [done? (or (empty? pending) (> idx 4))]
           (let [n-desired (count client-ids)
                 n-success (- n-desired (count pending))]

             (trove/log!
               {:level :debug,
                :id :sente.server/send-buffered-evs-to-clients,
                :data
                {:uid uid, :ws? (= conn-type :ws), :num-evs n-buffered-evs
                 :clients [n-success n-desired], :attempts (inc idx),
                 :msecs (- (enc/now-udt) udt-t0)}}))

           (let [ms-timeout
                 (let [ms-backoff (nth ms-backoffs idx)]
                   (+  ms-backoff (rand-int ms-backoff)))]

             ;; Allow some time for possible poller reconnects:
             (timers/call-after ms-timeout
               (fn [] (loop-fn (inc idx) pending)))))))]

    :then (loop-fn 0 (set client-ids))))

;;;; Client API

#?(:cljs (def ajax-call "Alias of `taoensso.encore/ajax-call`" enc/ajax-call))

   (defprotocol IChSocket
     (-chsk-connect!          [chsk])
     (-chsk-disconnect!       [chsk reason])
     (-chsk-reconnect!        [chsk reason])
     (-chsk-break-connection! [chsk opts])
     (-chsk-send!             [chsk ev opts]))

   (defn chsk-connect!    [chsk] (-chsk-connect!    chsk))
   (defn chsk-disconnect! [chsk] (-chsk-disconnect! chsk :requested-disconnect))
   (defn chsk-reconnect!
     "Cycles connection, useful for reauthenticating after login/logout, etc."
     [chsk] (-chsk-reconnect! chsk :requested-reconnect))

   (defn chsk-break-connection!
     "Breaks channel socket's underlying connection without doing a clean
     disconnect as in `chsk-disconnect!`. Useful for simulating broken
     connections in testing, etc.

     Options:

       `:close-ws?` - (Default: true)
         Allow WebSocket's `on-close` event to fire?
         Set to falsey to ~simulate a broken socket that doesn't realise
         it's broken."

     ([chsk] (-chsk-break-connection! chsk nil))
     ([chsk {:keys [close-ws?] :as opts
             :or   {close-ws? true}}]
      (-chsk-break-connection! chsk opts)))

   (defn chsk-send!
     "Sends `[ev-id ev-?data :as event]`, returns true on apparent success."
     ([chsk ev] (chsk-send! chsk ev {}))
     ([chsk ev ?timeout-ms ?cb] (chsk-send! chsk ev {:timeout-ms ?timeout-ms
                                                     :cb         ?cb}))
     ([chsk ev opts]
      (trove/log! {:level :trace, :id :sente.client/send-to-server, :data {:opts (assoc opts :cb (boolean (:cb opts))), :ev ev}})
      (-chsk-send! chsk ev opts)))

(defn- chsk-send->closed! [?cb-fn]
     (trove/log! {:level :warn, :id :sente.client/send-with-closed-chsk, :data {:cb? (boolean ?cb-fn)}})
     (when ?cb-fn (?cb-fn :chsk/closed))
     false)

   (defn- assert-send-args [x ?timeout-ms ?cb]
     (assert-event x)
     (assert (or (and (nil? ?timeout-ms) (nil? ?cb))
                 (and (enc/nat-int? ?timeout-ms)))
       (str "cb requires a timeout; timeout-ms should be a +ive integer: " ?timeout-ms))
     (assert (or (nil? ?cb) (ifn? ?cb)) (str "cb should be an ?ifn" (type ?cb))))

   (defn- pull-unused-cb-fn! [cbs-waiting_ ?cb-uuid]
     (when-let [cb-uuid ?cb-uuid]
       (swap-in! cbs-waiting_ [cb-uuid]
         (fn [?f] (swapped :swap/dissoc ?f)))))

   (defn- swap-chsk-state!
     "Atomically swaps the value of chk's :state_ atom."
     [chsk f]
     (let [[old-state new-state] #_(swap-vals! (:state_ chsk) f) ; Clj 1.9+
           (swap-in! (:state_ chsk)
             (fn [old-state]
               (let [new-state (f old-state)]
                 (swapped new-state [old-state new-state]))))]

       (when (not= old-state new-state)
         (let [old-open? (boolean (:open? old-state))
               new-open? (boolean (:open? new-state))

               open-changed? (not=     new-open?      old-open? )
               opened?       (and      new-open? (not old-open?))
               closed?       (and (not new-open?)     old-open?)
               first-open?   (and opened? (not (:ever-opened? old-state)))

               new-state ; Add transient state transitions, not in @state_
               (if-not open-changed?
                 (do             new-state)
                 (enc/assoc-when new-state
                   :open-changed? true
                   :opened?       opened?
                   :closed?       closed?
                   :first-open?   first-open?))]

           (cond
             opened? (trove/log! {:level :info, :id :sente.client/chsk-opened})
             closed? (trove/log! {:level :warn, :id :sente.client/chsk-closed, :data {:reason (get-in new-state [:last-close :reason] "unknown")}}))

           (let [output [old-state new-state open-changed?]]
             (async/put! (get-in chsk [:chs :state]) [:chsk/state output])
             open-changed?)))))

   (defn- chsk-state->closed [state reason]
     (truss/have? map? state)
     (truss/have?
       [:el #{:clean :unexpected
              :requested-disconnect
              :requested-reconnect
              :downgrading-ws-to-ajax
              :ws-ping-timeout :ws-error}]
       reason)

     (let [closing? (:open? state)
           m state
           m (dissoc m :udt-next-reconnect)
           m (assoc  m :open? false)]

       (if closing?
         (assoc m :last-close {:udt (enc/now-udt) :reason reason})
         (do    m))))

   (defn- receive-buffered-evs! [chs clj]
     (let [buffered-evs (truss/have vector? clj)]

       (trove/log!
         {:level :trace, :id :sente.client/receive-buffered-evs,
          :data {:num-evs (count buffered-evs), :clj clj}})

       (doseq [ev buffered-evs]
         (assert-event ev)
         ;; Should never receive :chsk/* events from server here:
         (let [[id] ev] (assert (not= (namespace id) "chsk")))
         (async/put! (:<server chs) ev))))

   (defn- handshake? [x]
     (and (vector? x) ; Nb support arb input (e.g. cb replies)
       (let [[x1] x] (= x1 :chsk/handshake))))

   (defn- receive-handshake! [chsk-type chsk clj]
     (truss/have? [:el #{:ws :ajax}] chsk-type)
     (truss/have? handshake? clj)

     (let [[_ [?uid _ ?handshake-data]] clj
           {:keys [chs ever-opened?_]} chsk
           first-handshake? (compare-and-set! ever-opened?_ false true)
           new-state
           {:type           chsk-type ; :auto -> e/o #{:ws :ajax}, etc.
            :open?          true
            :ever-opened?   true
            :uid            ?uid
            :handshake-data ?handshake-data}

           handshake-ev
           [:chsk/handshake
            [?uid nil ?handshake-data first-handshake?]]]

       (trove/log!
         {:level :debug, :id :sente.client/receive-handshake,
          :data {:ws? (= chsk-type :ws), :first? first-handshake?, :ev clj}})

       (assert-event handshake-ev)
       (swap-chsk-state! chsk
         (fn [m]
           (-> m
             (dissoc :udt-next-reconnect)
             (merge new-state))))

       (async/put! (:internal chs) handshake-ev)
       :handled))

#?(:clj
   (defmacro ^:private elide-require
     "`js/require` calls can cause issues with React Native static analysis even
     if they never execute, Ref. <https://github.com/ptaoussanis/sente/issues/247>."
     [& body]
     (when-not (enc/get-env {:as :bool} :sente-elide-js-require)
       `(do ~@body))))

#?(:cljs
   (def ^:private ?node-npm-websocket_
     "nnil iff the websocket npm library[1] is available.
     Easiest way to install:
       1. Add the lein-npm[2] plugin to your `project.clj`,
       2. Add: `:npm {:dependencies [[websocket \"1.0.23\"]]}`

     [1] Ref. <https://www.npmjs.com/package/websocket>
     [2] Ref. <https://github.com/RyanMcG/lein-npm>"

     (let [make-package-name (fn [prefix] (str prefix "socket"))
           require-fn (if (exists? js/require) js/require (constantly :no-op))]

       (delay           ; For React Native, Ref. #247
         (elide-require ; For React Native, Ref. #247
           (when (and node-target? (exists? js/require))
             (try
               (require-fn (make-package-name "web"))
               ;; In particular, catch 'UnableToResolveError'
               (catch :default e
                 (trove/log! {:level :error, :id :sente.client/no-npm-websockets, :error e})))))))))

#?(:cljs
   (defn- make-client-ws
     "Returns nil or a delay that can be dereffed to get a connected JS
     `ClientWebSocket`."
     [{:keys [uri-str headers on-error on-message on-close binary-type]
       :or   {binary-type "arraybuffer"}}]

     (when-let [WebSocket
                (or
                  (enc/oget goog/global           "WebSocket")
                  (enc/oget goog/global           "MozWebSocket")
                  (enc/oget @?node-npm-websocket_ "w3cwebsocket"))]
       (delay
         (let [socket (WebSocket. uri-str)]
           (doto socket
             (aset "binaryType" binary-type)
             (aset "onerror"    on-error)
             (aset "onmessage"  on-message) ; Nb receives both push & cb evs!
             ;; Fires repeatedly (on each connection attempt) while server is down:
             (aset "onclose"    on-close))

           (reify
             i/IClientWebSocket
             (cws-raw   [_]                            socket)
             (cws-send  [_ data]               (.send  socket data))
             (cws-close [_ code reason clean?] (.close socket reason clean?))))))))

(defn- get-client-csrf-token-str
  "Returns non-blank client CSRF token ?string from given token string
  or (fn [])->?string."
  [warn? token-or-fn]
  (when  token-or-fn
    (let [dynamic? (fn? token-or-fn)]
      (if-let [token (enc/as-?nblank (if dynamic? (token-or-fn) token-or-fn))]
        token
        (when-let [warn? (if (= warn? :dynamic) dynamic? warn?)]
          (trove/log!
            {:level :warn, :id :sente.client/no-csrf-token, :msg
             "WARNING: no client CSRF token provided. Connections will FAIL if server-side CSRF check is enabled (as it is by default)."}))))))

(comment (get-client-csrf-token-str false "token"))

(def client-unloading?_ (atom false))
#?(:cljs
   (truss/catching ; Not possible on Node, React Native, etc.
     (.addEventListener goog/global "beforeunload"
       (fn [event] (reset! client-unloading?_ true) nil))))

(defn- retry-connect-chsk!
  [chsk backoff-ms-fn connect-fn retry-count]
  (if (= retry-count 1)
    (do
      (trove/log! {:id :sente.client/try-reconnect})
      (connect-fn))

    (let [backoff-ms         (backoff-ms-fn retry-count)
          udt-next-reconnect (+ (enc/now-udt) backoff-ms)]

      (trove/log! {:id :sente.client/try-reconnect, :data {:attempt retry-count, :wait-msecs backoff-ms}})

      (timers/call-after backoff-ms
        (fn []
          (trove/log! {:id :sente.client/try-reconnect})
          (connect-fn)))

      (swap-chsk-state! chsk
        #(assoc % :udt-next-reconnect udt-next-reconnect)))))

(defrecord ChWebSocket
  ;; WebSocket-only IChSocket implementation
  ;; Handles (re)connections, cbs, etc.

  [client-id chs params headers packer url
   state_ ; {:type _ :open? _ :uid _ :csrf-token _ ...}
   conn-id_ retry-count_ ever-opened?_
   ws-kalive-ms ws-ping-timeout-ms ws-opts
   backoff-ms-fn ; (fn [nattempt]) -> msecs
   cbs-waiting_ ; {<cb-uuid> <fn> ...}
   socket_ ; ?[<socket> <socket-id>]
   udt-last-comms_
   ws-constructor]

  IChSocket
  (-chsk-disconnect! [chsk reason]
    (reset! conn-id_ nil) ; Disable auto retry
    (let [closed? (swap-chsk-state! chsk #(chsk-state->closed % reason))]
      (when-let [[s _sid] @socket_] (i/cws-close s 1000 "CLOSE_NORMAL" true))
      closed?))

  (-chsk-reconnect! [chsk reason]
    (-chsk-disconnect! chsk reason)
    (-chsk-connect!    chsk))

  (-chsk-break-connection! [chsk opts]
    (let [{:keys [close-ws? ws-code]
           :or   {ws-code 3000}} opts]

      (when-let [[s _sid]
                 (if-not close-ws?
                   ;; Suppress socket's `on-close` handler by breaking
                   ;; (own-socket?) socket ownership test
                   (reset-in! socket_ nil)
                   (do       @socket_))]
        (i/cws-close s ws-code "CLOSE_ABNORMAL" false))
      nil))

  (-chsk-send! [chsk ev opts]
    (enc/cond
      :let [{?cb-fn :cb} opts]
      (not (get @state_ :open?)) (chsk-send->closed! ?cb-fn)

      :let
      [{?timeout-ms :timeout-ms :keys [flush?]} opts
       ?cb-uuid (when ?cb-fn (enc/uuid-str 6))]

      :do (assert-send-args ev ?timeout-ms ?cb-fn)
      :do
      (on-packed packer true ev ?cb-uuid
        (fn [packed]

          (when-let [cb-uuid ?cb-uuid]
            (reset-in! cbs-waiting_ [cb-uuid] (truss/have ?cb-fn))
            (when-let [ms ?timeout-ms]
              (timers/call-after ms
                (fn []
                  (when-let [cb-fn* (pull-unused-cb-fn! cbs-waiting_ ?cb-uuid)]
                    (cb-fn* :chsk/timeout))))))

          (or
            (when-let [[s _sid] @socket_]
              (truss/try*
                (i/cws-send s packed)
                (reset! udt-last-comms_ (enc/now-udt))
                true
                (catch :all t
                  (trove/log! {:level :error, :id :sente.client/send-error, :error t}))))

            (do
              (when-let [cb-uuid ?cb-uuid]
                (let    [cb-fn* (or (pull-unused-cb-fn! cbs-waiting_ cb-uuid)
                                    (truss/have ?cb-fn))]
                  (cb-fn* :chsk/error)))

              (-chsk-reconnect! chsk :ws-error)))))

      :then true))

  (-chsk-connect! [chsk]
    (let [this-conn-id (reset! conn-id_ (enc/uuid-str))
          own-conn?    (fn [] (= @conn-id_ this-conn-id))

          connect-fn
          (fn connect-fn []
            (when (own-conn?)
              (let [;; ID for the particular candidate socket to be returned from
                    ;; this particular connect-fn call
                    this-socket-id (enc/uuid-str)
                    own-socket?
                    (fn []
                      (when-let [[_s sid] @socket_]
                        (= sid this-socket-id)))

                    retry-fn
                    (fn []
                      (when (and (own-conn?) (not @client-unloading?_))
                        (retry-connect-chsk! chsk backoff-ms-fn connect-fn
                          (swap! retry-count_ inc))))

                    on-error
                    #?(:cljs
                       (fn [ws-ev]
                         (when (own-socket?)
                           (trove/log! {:level :error, :id :sente.client/ws-error, :data {:ev ws-ev}})
                           (swap-chsk-state! chsk
                             #(assoc % :last-ws-error
                                {:udt (enc/now-udt), :ev ws-ev}))))

                       :clj
                       (fn [ex]
                         (when (own-socket?)
                           (trove/log! {:level :error, :id :sente.client/ws-error, :error ex})
                           (swap-chsk-state! chsk
                             #(assoc % :last-ws-error
                                {:udt (enc/now-udt), :ex ex})))))

                    on-message
                    (fn #?(:cljs [ws-ev] :clj [packed])
                      (reset! udt-last-comms_ (enc/now-udt))
                      (on-unpacked packer true #?(:clj packed :cljs (enc/oget ws-ev "data"))
                        (fn [[arb-msg-clj ?cb-uuid]] ; Receives both pushes (ev-clj) & cb replies (arb-reply-clj)
                          (or
                            (when (and (own-socket?) (handshake? arb-msg-clj))
                              (receive-handshake! :ws chsk arb-msg-clj)
                              (reset! retry-count_ 0)
                              :done/did-handshake)

                            (when (= arb-msg-clj :chsk/ws-ping)
                              (-chsk-send! chsk                 [:chsk/ws-pong] {:flush? true})
                              #_(async/put! (get chs :internal) [:chsk/ws-ping]) ; Would be better, but breaking
                              (async/put!   (get chs :<server)  [:chsk/ws-ping]) ; Odd choice for back compatibility
                              :done/sent-pong)

                            (if-let   [cb-uuid ?cb-uuid]
                              (if-let [cb-fn (pull-unused-cb-fn! cbs-waiting_ cb-uuid)]
                                (cb-fn arb-msg-clj)
                                (trove/log! {:level :warn, :id :sente.client/reply-without-cb, :data {:reply arb-msg-clj}}))
                              (receive-buffered-evs! chs arb-msg-clj))))))

                    on-close
                    ;; Fires repeatedly (on each connection attempt) while server down
                    (fn #?(:cljs [ws-ev] :clj [code reason _remote?])
                      (when (own-socket?)
                        (let [;; For codes, Ref. <https://www.rfc-editor.org/rfc/rfc6455.html#section-7.1.5>
                              last-ws-close ; For advanced debugging, etc.
                              #?(:clj
                                 {:udt    (enc/now-udt)
                                  :code   code
                                  :reason reason
                                  :clean? (= code 1000)}

                                 :cljs
                                 {:udt             (enc/now-udt)
                                  :code            (enc/oget ws-ev "code")
                                  :reason          (enc/oget ws-ev "reason")
                                  :clean? (boolean (enc/oget ws-ev "wasClean"))
                                  :ev                        ws-ev})

                              reason* (if (:clean? last-ws-close) :clean :unexpected)]

                          (swap-chsk-state! chsk
                            #(assoc (chsk-state->closed % reason*)
                               :last-ws-close last-ws-close))

                          (retry-fn))))

                    ?new-socket_
                    (when ws-constructor
                      (truss/try*
                        (ws-constructor
                          (merge ws-opts
                            {:on-error   on-error
                             :on-message on-message
                             :on-close   on-close
                             :headers    headers
                             :uri-str
                             (enc/merge-url-with-query-string url
                               (merge params ; 1st (don't clobber impl.):
                                 {:client-id  client-id
                                  :csrf-token (get-client-csrf-token-str :dynamic
                                                (:csrf-token @state_))}))}))

                        (catch :any t
                          (trove/log! {:level :error, :id :sente.client/ws-constructor-error, :error t}))))]

                (when-let [new-socket_ ?new-socket_]
                  (if-let [new-socket
                           (truss/try*
                             (force new-socket_)
                             (catch :default  t
                               (trove/log! {:level :error, :id :sente.client/ws-constructor-error, :error t})))]
                    (do
                      (when-let [[old-s _old-sid] (reset-in! socket_ [new-socket this-socket-id])]
                        ;; Close old socket if one exists
                        (trove/log! {:level :trace, :id :sente.client/close-old-websocket})
                        (i/cws-close old-s 1000 "CLOSE_NORMAL" true))
                      new-socket)
                    (retry-fn))))))]

      (reset! retry-count_ 0)

      (when (connect-fn)

        ;; Client-side loop to detect broken conns, Ref. #259
        (when-let [ms ws-kalive-ms]
          (let [loop-fn
                (fn loop-fn [udt-t0]
                  (timers/call-after ms
                    (fn []
                      (when (own-conn?)
                        (let [udt-t1 @udt-last-comms_]
                          (when-let [;; No conn send/recv activity w/in kalive window?
                                     no-activity? (= udt-t0 udt-t1)]

                            (trove/log!
                              {:level :trace, :id :sente.client/send-ping,
                               :data
                               {:ms-since-last-activity (- (enc/now-udt) udt-t1)
                                :timeout-ms ws-ping-timeout-ms}})

                            (-chsk-send! chsk [:chsk/ws-ping]
                              {:flush? true
                               :timeout-ms ws-ping-timeout-ms
                               :cb ; Server will auto reply
                               (fn [reply]
                                 (when (and (own-conn?) (not= reply "pong") #_(= reply :chsk/timeout))
                                   (trove/log! {:level :debug, :id :sente.client/ping-timeout})
                                   (-chsk-reconnect! chsk :ws-ping-timeout)))})))
                        (loop-fn @udt-last-comms_)))))]
            (loop-fn @udt-last-comms_)))

        chsk))))

(defn- new-ChWebSocket [opts csrf-token]
  (map->ChWebSocket
    (merge
      {:state_ (atom {:type :ws :open? false :ever-opened? false :csrf-token csrf-token})
       :conn-id_        (atom nil)
       :retry-count_    (atom 0)
       :ever-opened?_   (atom false)
       :cbs-waiting_    (atom {})
       :socket_         (atom nil)
       :udt-last-comms_ (atom nil)}
      opts)))

(def ^:private default-client-side-ajax-timeout-ms
  "We must set *some* client-side timeout otherwise an unpredictable (and
  probably too short) browser default will be used. Must be > server's
  :lp-timeout-ms."
  (enc/ms :secs 60))

#?(:cljs
   (defn- binary-val? [x]
     (or
       (when (exists? js/ArrayBuffer) (instance? js/ArrayBuffer x))
       (when (exists? js/Blob)        (instance? js/Blob        x)))))

#?(:cljs
   (defrecord ChAjaxSocket
     ;; Ajax-only IChSocket implementation
     ;; Handles (re)polling, etc.

     [client-id chs params packer url state_
      conn-id_ ever-opened?_
      backoff-ms-fn
      ajax-opts curr-xhr_]

     IChSocket
     (-chsk-disconnect! [chsk reason]
       (reset! conn-id_ nil) ; Disable auto retry
       (let [closed? (swap-chsk-state! chsk #(chsk-state->closed % reason))]
         (when-let [x @curr-xhr_] (.abort x))
         closed?))

     (-chsk-reconnect! [chsk reason]
       (-chsk-disconnect! chsk reason)
       (-chsk-connect!    chsk))

     (-chsk-break-connection! [chsk _opts]
       (when-let [x @curr-xhr_] (.abort x)) nil)

     (-chsk-send! [chsk ev opts]
       (enc/cond
         :let [{?cb-fn :cb} opts]
         (not (get @state_ :open?)) (chsk-send->closed! ?cb-fn)

         :let
         [{?timeout-ms :timeout-ms :keys [flush?]} opts
          ?cb-uuid   (when ?cb-fn :ajax)
          csrf-token (get-client-csrf-token-str :dynamic (get @state_ :csrf-token))]

         :do (assert-send-args ev ?timeout-ms ?cb-fn)
         :do
         (on-packed packer false ev ?cb-uuid
           (fn [packed]
             (let [binary-packer? (binary-val? packed)]
               (ajax-call url
                 (enc/merge ajax-opts
                   {:method     :post
                    :timeout-ms (or ?timeout-ms (get ajax-opts :timeout-ms) default-client-side-ajax-timeout-ms)
                    :resp-type  (if   binary-packer? :bin/array-buffer :text)
                    :body       (when binary-packer? packed)
                    :params
                    (conj
                      {:udt (enc/now-udt), :client-id client-id}
                      (if binary-packer?
                        {:binary? true}
                        {:ppstr   packed}))

                    :headers
                    (enc/assoc-some (get ajax-opts :headers)
                      {"X-CSRF-Token" csrf-token
                       "Content-Type"
                       (when binary-packer?
                         "application/octet-stream")})})

                 (fn ajax-cb [{:keys [?error ?content]}]
                   (enc/cond
                     (= ?error :timeout) (when ?cb-fn (?cb-fn :chsk/timeout))
                     ?error
                     (do
                       (swap-chsk-state! chsk #(chsk-state->closed % :unexpected))
                       (when ?cb-fn (?cb-fn :chsk/error)))

                     :do
                     (on-unpacked packer false ?content
                       (fn [[arb-reply-clj _]]
                         (if-let [cb-fn ?cb-fn]
                           (cb-fn      arb-reply-clj)
                           (when (not= arb-reply-clj :chsk/dummy-cb-200)
                             (trove/log! {:level :warn, :id :sente.client/reply-without-cb, :data {:reply arb-reply-clj}})))))

                     :do (swap-chsk-state! chsk #(assoc % :open? true))))))))

         :then true))

     (-chsk-connect! [chsk]
       (let [this-conn-id (reset! conn-id_ (enc/uuid-str))
             own-conn?    (fn [] (= @conn-id_ this-conn-id))

             poll-fn ; async-poll-for-update-fn
             (fn poll-fn [retry-count]
               (when (own-conn?)
                 (let [retry-fn
                       (fn []
                         (when (and (own-conn?) (not @client-unloading?_))
                           (let [retry-count* (inc retry-count)]
                             (retry-connect-chsk! chsk backoff-ms-fn
                               (fn connect-fn [] (poll-fn retry-count*))
                               (do                        retry-count*)))))]

                   (on-packed packer false :chsk/dummy-packer-test nil
                     (fn [packed]
                       (trove/log! {:level :trace, :id :sente.client/ajax-poll})
                       (reset! curr-xhr_
                         (ajax-call url
                           (enc/merge ajax-opts
                             {:method     :get
                              :xhr-cb-fn  (fn [xhr] (reset! curr-xhr_ xhr))
                              :timeout-ms (or (get ajax-opts :timeout-ms) default-client-side-ajax-timeout-ms)
                              :resp-type  (if (binary-val? packed) :bin/array-buffer :text)
                              :headers
                              (let [csrf-token (get-client-csrf-token-str :dynamic (get @state_ :csrf-token))]
                                (assoc (get ajax-opts :headers) "X-CSRF-Token" csrf-token))

                              :params
                              (enc/assoc-when params
                                {:udt        (enc/now-udt)
                                 :client-id  client-id
                                 :handshake? (when-not (get @state_ :open?) 1)})})

                           (fn ajax-cb [{:keys [?error ?content]}]
                             (enc/cond
                               (= ?error :timeout) (poll-fn 0)
                               ?error
                               (do
                                 (swap-chsk-state! chsk #(chsk-state->closed % :unexpected))
                                 (retry-fn))

                               :do
                               (on-unpacked packer false ?content
                                 (fn [[ev-clj _]] ; Ajax long-poller only for events only, never cb replies
                                   (let [handshake? (handshake? ev-clj)]
                                     (when handshake? (receive-handshake! :ajax chsk ev-clj))
                                     (swap-chsk-state! chsk #(assoc % :open? true))
                                     (poll-fn 0) ; Repoll asap

                                     (when-not (or handshake? (= ev-clj :chsk/timeout))
                                       (receive-buffered-evs! chs ev-clj))))))))))))))]

         (poll-fn 0)
         chsk))))

#?(:cljs
   (defn- new-ChAjaxSocket [opts csrf-token]
     (map->ChAjaxSocket
       (merge
         {:state_        (atom {:type :ajax :open? false :ever-opened? false :csrf-token csrf-token})
          :conn-id_      (atom nil)
          :ever-opened?_ (atom false)
          :curr-xhr_     (atom nil)}
         opts))))

#?(:cljs
   (defrecord ChAutoSocket
     ;; Dynamic WebSocket/Ajax IChSocket implementation
     ;; Wraps a swappable ChWebSocket/ChAjaxSocket

     [ws-chsk-opts ajax-chsk-opts state_
      impl_ ; ChWebSocket or ChAjaxSocket
      ]

     IChSocket
     (-chsk-break-connection! [chsk opts]   (when-let [impl @impl_] (-chsk-break-connection! impl opts)))
     (-chsk-disconnect!       [chsk reason] (when-let [impl @impl_] (-chsk-disconnect!       impl reason)))
     (-chsk-reconnect!        [chsk reason]
       (-chsk-disconnect! chsk reason)
       (-chsk-connect!    chsk))

     (-chsk-send! [chsk ev opts]
       (if-let [impl @impl_]
         (-chsk-send! impl ev opts)
         (let [{?cb-fn :cb} opts]
           (chsk-send->closed! ?cb-fn))))

     (-chsk-connect! [chsk]
       ;; Currently using a simplistic downgrade-only strategy.
       (let [ajax-chsk-opts (assoc ajax-chsk-opts :state_ state_)
             ws-chsk-opts   (assoc   ws-chsk-opts :state_ state_)

             ajax-chsk!
             (fn []
               (let [ajax-chsk (new-ChAjaxSocket ajax-chsk-opts (:csrf-token @state_))]
                 (remove-watch state_ :chsk/auto-ajax-downgrade)
                 (-chsk-connect! ajax-chsk)))

             ws-chsk!
             (fn []
               (let [ws-chsk (new-ChWebSocket ws-chsk-opts (:csrf-token @state_))
                     downgraded?_ (atom false)]

                 (add-watch state_ :chsk/auto-ajax-downgrade
                   (fn [_ _ old-state new-state]
                     (enc/when-let [state-changed? (not= old-state new-state)
                                    impl           @impl_
                                    ever-opened?_  (:ever-opened?_ impl)
                                    never-opened?  (not @ever-opened?_)
                                    ws-error       (:last-ws-error new-state)]

                       (when (compare-and-set! downgraded?_ false true)
                         (trove/log! {:level :warn, :id :sente.client/downgrade-to-ajax})
                         (-chsk-disconnect! impl :downgrading-ws-to-ajax)
                         (reset! impl_ (ajax-chsk!))))))

                 (-chsk-connect! ws-chsk)))]

         (reset! impl_ (or (ws-chsk!) (ajax-chsk!)))
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
           protocol (truss/have [:el #{"http:" "https:"}] protocol)
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

     Required arguments:
       path              ; Channel socket server route/path (typically `/chsk`)
       ?csrf-token-or-fn ; CSRF string or (fn [])->string to match token expected by server.
                         ; nil => server not expecting any CSRF token.

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
       :ajax-opts      ; Base opts map provided to `taoensso.encore/ajax-call`, see
                       ; relevant docstring for more info.
       :wrap-recv-evs? ; Should events from server be wrapped in [:chsk/recv _]?

       :ws-kalive-ms       ; Max msecs to allow WebSocket inactivity before client sends ping to server.
       :ws-ping-timeout-ms ; Max msecs to wait for ws-kalive ping response before concluding conn is broken.

       :ws-constructor ; Advanced, (fn [{:keys [uri-str headers on-message on-error on-close]}]
                       ; => nil or delay that can be dereffed to get a connected `ClientWebSocket`."

     [path ?csrf-token-or-fn &
      [{:as   opts
        :keys [type protocol host port params headers recv-buf-or-n packer
               ws-constructor ws-kalive-ms ws-ping-timeout-ms ws-opts
               client-id ajax-opts wrap-recv-evs? backoff-ms-fn]

        :or   {type           :auto
               recv-buf-or-n  (async/sliding-buffer 2048) ; Mostly for buffered-evs
               packer         :edn
               client-id      (or (:client-uuid opts) ; Backwards compatibility
                                  (enc/uuid-str))
               wrap-recv-evs? false
               backoff-ms-fn  enc/exp-backoff

               ws-kalive-ms       20000
               ws-ping-timeout-ms 5000
               ws-constructor     #?(:cljs make-client-ws :clj nil)}}]]

     (truss/have? [:in #{:ajax :ws :auto}] type)
     (truss/have? enc/nblank-str? client-id)

     ;; Check once now to trigger possible warning
     (get-client-csrf-token-str true ?csrf-token-or-fn)

     (let [ws-kalive-ms (when ws-kalive-ms (quot ws-kalive-ms 2)) ; Ref. #455
           packer (coerce-packer packer)

           [ws-url ajax-url]
           (let [;; Not available with React Native, etc.
                 ;; Must always provide explicit path for Java client.
                 win-loc  #?(:clj nil :cljs (enc/get-win-loc))
                 path     (truss/have (or path (:pathname win-loc)))]

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
           {:internal (async/chan (async/sliding-buffer 128))
            :state    (async/chan (async/sliding-buffer 10))
            :<server
            (let [;; Nb must be >= max expected buffered-evs size:
                  buf (async/sliding-buffer 512)]
              (if wrap-recv-evs?
                (async/chan buf (map (fn [ev] [:chsk/recv ev])))
                (async/chan buf)))}

           ws-ping-timeout-ms
           (cond
             (contains? opts :ws-ping-timeout-ms)
             (do   (get opts :ws-ping-timeout-ms))

             (contains? opts :ws-kalive-ping-timeout-ms) ; Back compatibility
             (do   (get opts :ws-kalive-ping-timeout-ms))

             :else ws-ping-timeout-ms)

           common-chsk-opts
           {:client-id client-id
            :chs       private-chs
            :params    params
            :headers   headers
            :packer    packer
            :ws-kalive-ms       ws-kalive-ms
            :ws-ping-timeout-ms ws-ping-timeout-ms
            :ws-constructor     ws-constructor}

           ws-chsk-opts
           (merge common-chsk-opts
             {:url           ws-url
              :backoff-ms-fn backoff-ms-fn
              :ws-opts       ws-opts})

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
               :ws      (new-ChWebSocket    ws-chsk-opts ?csrf-token-or-fn)
               :ajax
               #?(:cljs (new-ChAjaxSocket ajax-chsk-opts ?csrf-token-or-fn)
                  :clj  (throw (UnsupportedOperationException.
                                 "Only :ws channel socket type supported for clj")))
               :auto
               #?(:cljs (new-ChAutoSocket auto-chsk-opts ?csrf-token-or-fn)
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

         (trove/log! {:level :warn, :id :sente.client/error-creating-chsk}))))

;;;; Event-msg routers (handler loops)

(defn- -start-chsk-router!
  [server? ch-recv event-msg-handler opts]
  (let [{:keys [trace-evs? error-handler simple-auto-threading?]} opts
        ch-ctrl (async/chan)

        execute1
        #?(:cljs (fn [f] (f))
           :clj
           (if simple-auto-threading?
             (fn [f] (future-call f))
             (fn [f] (f))))]

    (async/go-loop []
      (let [[v p] (async/alts! [ch-recv ch-ctrl])
            stop? (or (= p ch-ctrl) (nil? v))]

        (when-not stop?
          (let [{:as event-msg :keys [event]} v]

            (execute1
              (fn []
                (truss/try*
                  (when trace-evs? (trove/log! {:level :trace, :id :sente.router/event, :data {:event event}}))
                  (event-msg-handler
                    (if server?
                      (truss/have! server-event-msg? event-msg)
                      (truss/have! client-event-msg? event-msg)))

                  (catch :all t1
                    (truss/try*
                      (if-let [eh error-handler]
                        (eh t1 event-msg)
                        (trove/log! {:level :error, :id :sente.router/ev-msg-handler-error, :error t1, :data {:event event}}))
                      (catch :all t2
                        (trove/log! {:level :error, :id :sente.router/error-handler-error,  :error t2, :data {:event event}})))))))

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

(def event-msg?
  "Alias for:
  Cljs: `client-event-msg?`.
  Clj:  `server-event-msg?`.

  If you're using a Clj client or Cljs server, use the above utils
  directly instead."
  #?(:clj server-event-msg? :cljs client-event-msg?))

(def make-channel-socket!
  "Alias for:
  Cljs: `make-channel-socket-client!`.
  Clj:  `make-channel-socket-server!`.

  If you're using a Clj client or Cljs server, use the above utils
  directly instead. See above docstrings for details."
  #?(:clj  make-channel-socket-server!
     :cljs make-channel-socket-client!))

(def start-chsk-router!
  "Alias for:
  Cljs: `start-client-chsk-router!`.
  Clj:  `start-server-chsk-router!`.

  If you're using a Clj client or Cljs server, use the above utils
  directly instead. See above docstrings for details."
  #?(:clj  start-server-chsk-router!
     :cljs start-client-chsk-router!))
