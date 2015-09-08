(ns taoensso.sente-node
  "The server code for sente adapter for node.js"
  (:require
   [clojure.string     :as str]
   [cljs.core.async :as async  :refer (put! chan)]
   [taoensso.encore    :as enc    :refer (swap-in! reset-in! swapped)]
   [taoensso.timbre]
   [taoensso.sente.interfaces :as interfaces]
   )
  (:require-macros [taoensso.encore :refer (have have?)]
                   [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]
                   [cljs.core.async.macros :refer [go go-loop]])
  )

;; ;;;; Events
;; ;; * Clients & server both send `event`s and receive (i.e. route) `event-msg`s.

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

(defn format [s t]
  (.replace s "%s" t))

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

(defn event-msg? [x]
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


(defn- put-event-msg>ch-recv!
  "All server-side `event-msg`s go through this."
  [ch-recv {:as ev-msg :keys [event ?reply-fn]}]
  (let [[ev-id ev-?data :as valid-event] (as-event event)
        ev-msg* (merge ev-msg {:event     valid-event
                               :?reply-fn ?reply-fn
                               :id        ev-id
                               :?data     ev-?data})]
    (if-not (event-msg? ev-msg*)
      (warnf "Bad ev-msg: %s" ev-msg) ; Log 'n drop
      (put! ch-recv ev-msg*))))

;; ;;;; Packing
;; ;; * Client<->server payloads are arbitrary Clojure vals (cb replies or events).
;; ;; * Payloads are packed for client<->server transit.
;; ;; * Packing includes ->str encoding, and may incl. wrapping to carry cb info.

(defn- unpack* "pstr->clj" [packer pstr]
  (try
    (interfaces/unpack packer (have string? pstr))
    (catch :default t
      (debugf "Bad package: %s (%s)" pstr t)
      [:chsk/bad-package pstr]
      ; Let client rethrow on bad pstr from server
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
  (have? string? prefixed-pstr)
  (let [prefix   (enc/substr prefixed-pstr 0 1)
        pstr     (enc/substr prefixed-pstr 1)
        clj      (unpack* packer pstr) ; May be un/wrapped
        wrapped? (case prefix "-" false "+" true)
        [clj ?cb-uuid] (if wrapped? clj [clj nil])
        ?cb-uuid (if (= 0 ?cb-uuid) :ajax-cb ?cb-uuid)]
    (tracef "Unpacking: %s -> %s" prefixed-pstr [clj ?cb-uuid])
    [clj ?cb-uuid]))

;; (comment
;;   (do (require '[taoensso.sente.packers.transit :as transit])
;;       (def edn-packer   interfaces/edn-packer)
;;       (def flexi-packer (transit/get-flexi-packer)))
;;   (unpack edn-packer   (pack edn-packer   nil          "hello"))
;;   (unpack flexi-packer (pack flexi-packer nil          "hello"))
;;   (unpack flexi-packer (pack flexi-packer {}           [:foo/bar {}] "my-cb-uuid"))
;;   (unpack flexi-packer (pack flexi-packer {:json true} [:foo/bar {}] "my-cb-uuid"))
;;   (unpack flexi-packer (pack flexi-packer {}           [:foo/bar {}] :ajax-cb)))

;; ;;;; Server API

(declare ^:private send-buffered-evs>ws-clients!
         ^:private send-buffered-evs>ajax-clients!)


(defn make-channel-socket!
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

  [web-server-adapter ; Actually a net-ch-adapter, but that may be confusing
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
         (have? #(satisfies? interfaces/IAsyncNetworkChannelAdapter %)
                web-server-adapter)]}

  (let [packer  (interfaces/coerce-packer packer)
        ch-recv (chan recv-buf-or-n)
        conns_  (atom {:ws   {} ; {<uid> {<client-id> <net-ch>}}
                       :ajax {} ; {<uid> {<client-id> [<?net-ch> <udt-last-connected>]}}
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
                                 ;; cheap, and this approach is preferable to
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
                          :ws   (send-buffered-evs>ws-clients!   conns_
                                                                 uid buffered-evs-ppstr)
                          :ajax (send-buffered-evs>ajax-clients! conns_
                                                                 uid buffered-evs-ppstr))))))]

            (if (= ev [:chsk/close]) ; Currently undocumented
              (do
                (debugf "Chsk closing (client may reconnect): %s" uid)
                (when flush?
                  (doseq [type [:ws :ajax]]
                    (flush-buffer! type)))

                (doseq [net-ch (vals (get-in @conns_ [:ws uid]))]
                  (interfaces/close! net-ch))

                (doseq [[?net-ch _] (vals (get-in @conns_ [:ajax uid]))]
                  (when-let [net-ch ?net-ch]
                    (interfaces/close! net-ch))))

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
       (interfaces/ring-req->net-ch-resp web-server-adapter ring-req
                                         {:on-open
                                          (fn [net-ch]
                                            (let [params        (get ring-req :params)
                                                  ppstr         (get params   :ppstr)
                                                  client-id     (get params   :client-id)
                                                  [clj has-cb?] (unpack packer ppstr)]

                                              (put-event-msg>ch-recv! ch-recv
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
                                                                                  (interfaces/send! net-ch
                                                                                                    (pack packer (meta resp-clj) resp-clj)
                                                                                                    :close-after-send)))}))

                                              (when-not has-cb?
                                                (tracef "Chsk send (ajax reply): dummy-cb-200")
                                                (interfaces/send! net-ch
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
               (put-event-msg>ch-recv! ch-recv
                                       (merge ev-msg-const
                                              {:client-id client-id
                                               :ring-req  ring-req
                                               :event     event
                                               :?reply-fn ?reply-fn
                                               :uid       uid})))

             handshake!
             (fn [net-ch]
               (tracef "Handshake!")
               (let [?handshake-data (handshake-data-fn ring-req)
                     handshake-ev
                     (if-not (nil? ?handshake-data) ; Micro optimization
                       [:chsk/handshake [uid csrf-token ?handshake-data]]
                       [:chsk/handshake [uid csrf-token]])]
                 (interfaces/send! net-ch
                                   (pack packer nil handshake-ev)
                                   (not websocket?))))]

         (if (str/blank? client-id)
           (let [err-msg "Client's Ring request doesn't have a client id. Does your server have the necessary keyword Ring middleware (`wrap-params` & `wrap-keyword-params`)?"]
             (errorf (str err-msg ": %s") ring-req)
             (throw (ex-info err-msg {:ring-req ring-req})))

           (interfaces/ring-req->net-ch-resp web-server-adapter ring-req
                                             {:on-open
                                              (fn [net-ch]
                                                (if websocket?
                                                  (do ; WebSocket handshake
                                                    (tracef "New WebSocket channel: %s (%s)"
                                                            uid (str net-ch)) ; _Must_ call `str` on net-ch
                                                    (reset-in! conns_ [:ws uid client-id] net-ch)
                                                    (when (connect-uid! :ws uid)
                                                      (receive-event-msg! [:chsk/uidport-open]))
                                                    (handshake! net-ch))

                                                  ;; Ajax handshake/poll connection:
                                                  (let [initial-conn-from-client?
                                                        (swap-in! conns_ [:ajax uid client-id]
                                                                  (fn [?v] (swapped [net-ch (enc/now-udt)] (nil? ?v))))

                                                        handshake? (or initial-conn-from-client?
                                                                       (:handshake? params))]

                                                    (when (connect-uid! :ajax uid)
                                                      (receive-event-msg! [:chsk/uidport-open]))

                                                    ;; Client will immediately repoll:
                                                    (when handshake? (handshake! net-ch)))))

                                              :on-msg ; Only for WebSockets
                                              (fn [net-ch req-ppstr]
                                                (let [[clj ?cb-uuid] (unpack packer req-ppstr)]
                                                  (receive-event-msg! clj ; Should be ev
                                                                      (when ?cb-uuid
                                                                        (fn reply-fn [resp-clj] ; Any clj form
                                                                          (tracef "Chsk send (ws reply): %s" resp-clj)
                                                                          ;; true iff apparent success:
                                                                          (interfaces/send! net-ch
                                                                                            (pack packer (meta resp-clj) resp-clj ?cb-uuid)))))))

                                              :on-close ; We rely on `on-close` to trigger for _every_ conn!
                                              (fn [net-ch status]
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
                                                              (fn [[net-ch udt-last-connected]] [nil udt-last-connected]))

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


(defn- send-buffered-evs>ws-clients!
  "Actually pushes buffered events (as packed-str) to all uid's WebSocket conns."
  [conns_ uid buffered-evs-pstr]
  (tracef "send-buffered-evs>ws-clients!: %s" buffered-evs-pstr)
  (doseq [net-ch (vals (get-in @conns_ [:ws uid]))]
    (interfaces/send! net-ch buffered-evs-pstr)))


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
        client-ids-unsatisfied (keys (get-in @conns_ [:ajax uid]))]
    (when-not (empty? client-ids-unsatisfied)
      ;; (tracef "client-ids-unsatisfied: %s" client-ids-unsatisfied)
      (go-loop [n 0 client-ids-satisfied #{}]
               (let [?pulled ; nil or {<client-id> [<?net-ch> <udt-last-connected>]}
                     (swap-in! conns_ [:ajax uid]
                               (fn [m] ; {<client-id> [<?net-ch> <udt-last-connected>]}
                                 (let [ks-to-pull (remove client-ids-satisfied (keys m))]
                                   ;; (tracef "ks-to-pull: %s" ks-to-pull)
                                   (if (empty? ks-to-pull)
                                     (swapped m nil)
                                     (swapped
                                      (reduce
                                       (fn [m k]
                                         (let [[?net-ch udt-last-connected] (get m k)]
                                           (assoc m k [nil udt-last-connected])))
                                       m ks-to-pull)
                                      (select-keys m ks-to-pull))))))]
                 (have? [:or nil? map?] ?pulled)
                 (let [?newly-satisfied
                       (when ?pulled
                         (reduce-kv
                          (fn [s client-id [?net-ch _]]
                            (if (or (nil? ?net-ch)
                                    ;; net-ch may have closed already (`send!` will noop):
                                    (not (interfaces/send! ?net-ch buffered-evs-pstr
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

;; ;;;; Client API
;; ;;;; Router wrapper

(defn start-chsk-router!
  "Creates a go-loop to call `(event-msg-handler <event-msg>)` and returns a
  `(fn stop! [])`. Catches & logs errors. Advanced users may choose to instead
  write their own loop against `ch-recv`."
  [ch-recv event-msg-handler & [{:as opts :keys [trace-evs? error-handler]}]]
  (let [ch-ctrl (chan)]
    (go-loop []
             (let [[v p] (async/alts! [ch-recv ch-ctrl])
                   stop? (enc/kw-identical? p  ch-ctrl)]

               (when-not stop?
                 (let [{:as event-msg :keys [event]} v
                       [_ ?error]
                       (enc/catch-errors
                        (when trace-evs? (tracef "Pre-handler event: %s" event))
                        (event-msg-handler (have :! event-msg? event-msg)))]

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

;; ;;;; Deprecated


;; (defn start-chsk-router-loop!
;;   "DEPRECATED: Please use `start-chsk-router!` instead."
;;   [event-msg-handler ch-recv]
;;   (start-chsk-router! ch-recv
;;     ;; Old handler form: (fn [ev-msg ch-recv])
;;     (fn [ev-msg] (event-msg-handler ev-msg (:ch-recv ev-msg)))))


;; (defn set-logging-level! "DEPRECATED. Please use `timbre/set-level!` instead."
;;   [level] (timbre/set-level! level))
