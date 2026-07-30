(ns taoensso.sente-tests
  "Automated unit, regression, and JVM integration tests for Sente.

  Browser-specific behaviour is still checked manually against the
  reference example project."

  (:require
   [clojure.test                     :as test :refer [deftest testing is]]
   ;; [clojure.test.check            :as tc]
   ;; [clojure.test.check.generators :as tc-gens]
   ;; [clojure.test.check.properties :as tc-props]
   [clojure.string  :as str]
   [taoensso.encore :as enc]

   #?(:clj  [clojure.core.async :as async]
      :cljs [cljs.core.async    :as async])
   #?(:clj [org.httpkit.server              :as hk])
   #?(:clj [ring.core.protocols              :as ring-protocols])
   #?(:clj [ring.middleware.keyword-params   :as ring-keyword-params])
   #?(:clj [ring.middleware.params           :as ring-params])
   [taoensso.sente                         :as sente]
   #?(:clj [taoensso.sente.java-ws-client :as java-ws])
   #?(:clj [taoensso.sente.server-adapters.http-kit :as http-kit])
   #?(:clj [taoensso.sente.server-adapters.community.jetty :as jetty])

   [taoensso.sente.interfaces      :as i]
   [taoensso.sente.packers.edn     :as ep]
   [taoensso.sente.packers.transit :as tp]
   [taoensso.sente.packers.msgpack :as mp]
   [taoensso.sente.packers.gzip    :as gz]))

(comment
  (remove-ns      'taoensso.sente-tests)
  (test/run-tests 'taoensso.sente-tests))

;;;;

(defn- getenv [k]
  #?(:clj  (System/getenv k)
     :cljs (some-> js/process .-env (aget k))))

(def ^:dynamic *ci?* (or (getenv "CI") (getenv "GITHUB_ACTIONS")))
(def ^:dynamic *timeout-msecs* (if *ci?* 32000 4000))
(def ^:dynamic *bench-laps*    (if *ci?* 10    1e2))

;;;; Test data

(defn get-test-data []
  #_{:a :A :b :B :c "foo", :v (vec (range 128)), :s (set (range 128))}
  {:ids (vec (repeatedly (if *ci?* 16 64) (fn [] (rand-int 32767))))
   :data
   (vec
     (repeatedly (if *ci?* 16 128)
       (fn []
         {:uuid          (random-uuid)
          :uuid-string   (enc/uuid-str)
          :uuid-strings  (vec (repeatedly 4 #(enc/uuid-str 8)))
          :double        (rand 65536)
          :doubles       (vec (repeatedly 16 #(rand 65536)))
          :int           (rand-int 65536)
          :ints          (vec (repeatedly 16 #(rand-int 65536)))
          :date-str      (str (enc/now-inst))
          :bool          (rand-nth [true false])
          :bools         (vec (repeatedly 16 #(rand-nth [true false])))
          [nil #{:x :y}] {:complex-key? true}
          "bed6400a-785e-4a83-a41d-e5b566cd8950" :long-key1
          "1dfe33be-a765-4017-ad36-4e2fbc9d7d9b" :long-key2
          "7de8a506-acc9-4b33-9d19-918d1c5f5034" :long-key3
          "37b4e00a-f8a4-4646-95b1-612f7640f011" :long-key4})))})

(def td1 (get-test-data))

;;;; Packers

(defn roundtrip
  "Returns Clj/s promise."
  ([data   packer cb-fn]
   (i/pack packer nil data
     (fn [packed]
       (i/unpack packer nil    (get packed   :value)
         (fn [unpacked] (cb-fn (get unpacked :value)))))))

  ([data packer]
   #?(:clj                  (let [p (promise)] (roundtrip data packer (fn [v] (deliver p v))) p)
      :cljs (js/Promise. (fn [resolve _reject] (roundtrip data packer (fn [v] (resolve   v))))))))

(comment @(roundtrip :data (ep/get-packer)))

(deftest test-event-api
  (let [event [::event {:value 1}]
        ch    (async/chan)
        client-msg
        {:ch-recv ch, :send-fn identity, :state (atom {})
         :event event, :id ::event, :?data {:value 1}}
        server-msg
        {:ch-recv ch, :send-fn identity
         :connected-uids (atom {}), :send-buffers (atom {})
         :ring-req {}, :client-id "client-1", :uid ::user
         :event event, :id ::event, :?data {:value 1}, :?reply-fn nil}]
    (is (nil? (sente/validate-event event)))
    (doseq [[given error]
            [[::event :wrong-type]
             [[::event nil nil] :wrong-length]
             [["event"] :wrong-id-type]
             [[:event] :unnamespaced-id]]]
      (is (contains? (sente/validate-event given) error)))
    (is (sente/event? event))
    (is (not (sente/event? [:event])))
    (is (identical? event (sente/assert-event event)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (sente/assert-event [:event])))
    (is (= event (sente/as-event event)))
    (is (= [:chsk/bad-event [:event]] (sente/as-event [:event])))
    (doseq [[reply error?]
            [[:chsk/closed true] [:chsk/timeout true] [:chsk/error true]
             [:reply false] [nil false]]]
      (is (= error?       (sente/cb-error?   reply)))
      (is (= (not error?) (sente/cb-success? reply))))
    (is (sente/client-event-msg? client-msg))
    (is (sente/server-event-msg? server-msg))
    (is (sente/event-msg? #?(:clj server-msg :cljs client-msg)))
    (is (not (sente/client-event-msg? (dissoc client-msg :state))))
    (is (not (sente/server-event-msg? (assoc server-msg :event [:event]))))
    (async/close! ch)))

(defn test-promise [p f] ; f is test-fn
  #?(:clj (when p (f (deref p *timeout-msecs* :timeout)))
     :cljs
     (when p
       (test/async done
         (let [done  (let [run?_ (atom false)] (fn [] (when (compare-and-set! run?_ false true) (done))))
               timer (js/setTimeout #(do (f :timeout) (done)) *timeout-msecs*)]
           (-> p
             (.then    (fn [v] (js/clearTimeout timer) (f v)))
             (.catch   (fn [e] (js/clearTimeout timer) (f e)))
             (.finally (fn [ ] (js/clearTimeout timer) (done)))))))))

(do
  (deftest test-packer-edn        (test-promise (roundtrip td1                 (ep/get-packer))                   #(is (= td1 %))))
  (deftest test-packer-transit    (test-promise (roundtrip td1                 (tp/get-packer))                   #(is (= td1 %))))
  (deftest test-packer-msgpack    (test-promise (roundtrip td1                 (mp/get-packer))                   #(is (= td1 %))))
  (deftest test-packer-gz+edn     (test-promise (roundtrip td1 (gz/wrap-packer (ep/get-packer) {:binary? false})) #(is (= td1 %))))
  (deftest test-packer-gz+transit (test-promise (roundtrip td1 (gz/wrap-packer (tp/get-packer) {:binary? false})) #(is (= td1 %))))
  (deftest test-packer-gz+msgpack (test-promise (roundtrip td1 (gz/wrap-packer (mp/get-packer) {:binary?  true})) #(is (= td1 %)))))

(deftest test-packer-msgpack-limits
  (let [packed_ (atom nil)]
    (i/pack (mp/get-packer) :ws [[]] #(reset! packed_ (:value %)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (i/unpack (mp/get-packer {:max-nesting-depth 1}) :ws @packed_ identity)))))

(deftest test-gzip-expansion-limit
  (testing "Gzip unpacking rejects output beyond the configured limit"
    (let [packer  (gz/wrap-packer (ep/get-packer)
                    {:binary? false, :max-uncompressed-bytes 64})
          payload (apply str (repeat 2048 "x"))]
      #?(:clj
         (let [packed_ (atom nil)]
           (i/pack packer :ws payload #(reset! packed_ (:value %)))
           (is (thrown? Exception
                 (i/unpack packer :ws @packed_ (fn [_])))))

         :cljs
         (test-promise
           (js/Promise.
             (fn [resolve reject]
               (i/pack packer :ws payload
                 (fn [{:keys [value error]}]
                   (if error
                     (reject error)
                     (i/unpack packer :ws value resolve))))))
           #(is (some? (:error %))))))))

(deftest test-gzip-malformed-input
  (let [packer (gz/wrap-packer (ep/get-packer) {:binary? false})]
    #?(:clj
       (doseq [packed [(byte-array 0) (byte-array [2])]]
         (is (thrown? Exception (i/unpack packer :ws packed identity))))

       :cljs
       (doseq [packed [(js/Uint8Array. 0) (js/Uint8Array. #js [2])]]
         (test-promise
           (js/Promise.
             (fn [resolve]
               (i/unpack packer :ws packed resolve)))
           #(is (some? (:error %))))))))

;;;; Security: CSRF & origin checks (server-only)

#?(:clj
   (deftest test-allow-origin?
     (testing "allow-origin? (also usable as an origin-based CSRF defense)"
       (is (true?  (sente/allow-origin? :all                 {:headers {"origin"  "http://site.com"}}))         ":all allows any origin")
       (is (true?  (sente/allow-origin? #{"http://site.com"} {:headers {"origin"  "http://site.com"}}))         "Exact Origin match")
       (is (true?  (sente/allow-origin? #{"http://site.com"} {:headers {"referer" "http://site.com/x"}}))        "Referer fallback when no Origin")
       (is (false? (sente/allow-origin? #{"http://site.com"} {:headers nil}))                                    "No Origin/Referer => reject")
       (is (false? (sente/allow-origin? #{"http://site.com"} {:headers {"origin"  "http://evil.com"}}))          "Wrong Origin => reject")
       (is (false? (sente/allow-origin? #{"http://site.com"} {:headers {"referer" "http://evil.com/"}}))         "Wrong Referer => reject")
       (is (false? (sente/allow-origin? #{"http://site.com"} {:headers {"referer" "http://site.com.evil.com/"}})) "Prefix-spoofed Referer => reject"))))

#?(:clj
   (deftest test-valid-csrf-token?
     (testing "valid-csrf-token? (Sente's built-in token-equality check)"
       (let [tok-fn (fn [_] "secret-tok")]
         (is (true?  (sente/valid-csrf-token? tok-fn {:params  {:csrf-token    "secret-tok"}})) "Request-param token match")
         (is (true?  (sente/valid-csrf-token? tok-fn {:headers {"x-csrf-token" "secret-tok"}})) "x-csrf-token header match")
         (is (true?  (sente/valid-csrf-token? tok-fn {:headers {"x-xsrf-token" "secret-tok"}})) "x-xsrf-token header match")
         (is (true?  (sente/valid-csrf-token? tok-fn
                       {:headers {"sec-websocket-protocol" "chat, sente-csrf-v1.secret-tok"}}))
           "WebSocket subprotocol token match")
         (is (false? (sente/valid-csrf-token? tok-fn {:params  {:csrf-token    "wrong-tok"}}))  "Token mismatch => false")
         (is (false? (sente/valid-csrf-token? tok-fn {}))                                       "Missing client token => false")
         (is (false? (sente/valid-csrf-token? (fn [_] nil) {:params {:csrf-token "x"}}))        "Missing reference token => false")
         (is (false? (sente/valid-csrf-token? nil          {:params {:csrf-token "x"}}))        "Nil csrf-token-fn => false (no NPE)")
         (is (true?  (sente/valid-csrf-token? (fn [_] :sente/skip-CSRF-check) {}))              "`:sente/skip-CSRF-check` => valid (skip)")))))

#?(:clj
   ;; Minimal `IServerChanAdapter` for testing request rejection. The non-rejected
   ;; path returns a sentinel so we can confirm a request reached the adapter.
   (def ^:private stub-sch-adapter
     (reify i/IServerChanAdapter
       (ring-req->server-ch-resp [_ _ring-req _callbacks-map]
         {:status 200 :body ::reached-adapter}))))

#?(:clj
   (defn- pack-sync [packer websocket? value]
     (let [result (promise)]
       (i/pack packer websocket? value #(deliver result %))
       (:value (deref result *timeout-msecs* {:error :timeout})))))

#?(:clj
   (defn- unpack-sync [packer websocket? value]
     (let [result (promise)]
       (i/unpack packer websocket? value #(deliver result %))
       (:value (deref result *timeout-msecs* {:error :timeout})))))

#?(:cljs
   (defn- pack-now [packer websocket? value]
     (let [result_ (atom ::pending)]
       (i/pack packer websocket? value #(reset! result_ (:value %)))
       @result_)))

#?(:clj
   (defn- recording-sch-adapter [connections_]
     (reify i/IServerChanAdapter
       (ring-req->server-ch-resp [_ ring-req callbacks]
         (let [websocket? (:websocket? ring-req)
               open?_     (atom true)
               send?_     (atom true)
               attempts_  (atom [])
               sent_      (atom [])
               sch
               (reify i/IServerChan
                 (sch-open? [_] @open?_)
                 (sch-close! [this]
                   (when (compare-and-set! open?_ true false)
                     (when-let [on-close (:on-close callbacks)]
                       (on-close this websocket? :closed))
                     true))
                 (sch-send! [this _websocket? msg]
                   (swap! attempts_ conj msg)
                   (when (and @open?_ @send?_)
                     (swap! sent_ conj msg)
                     (when-not websocket? (i/sch-close! this))
                     true)))
               conn
               {:ring-req ring-req, :sch sch, :open?_ open?_, :send?_ send?_
                :attempts_ attempts_, :sent_ sent_}]
           (swap! connections_ conj conn)
           ((:on-open callbacks) sch websocket?)
           {:status (if websocket? 101 200)})))))

#?(:clj
   (defn- await-pred [pred]
     (loop [attempts (quot *timeout-msecs* 10)]
       (cond
         (pred) true
         (pos? attempts) (do (Thread/sleep 10) (recur (dec attempts)))
         :else false))))

#?(:clj
   (deftest test-builtin-csrf-check
     (testing "Built-in (default) token CSRF check, via the handler"
       (let [post-fn  (fn [opts] (:ajax-post-fn (sente/make-channel-socket-server! stub-sch-adapter opts)))
             reached? (fn [resp] (= (:body resp) ::reached-adapter))]

         (testing "missing token => rejected (403)"
           (is (= 403 (:status ((post-fn {}) {})))))

         (testing "matching token => passes"
           (is (reached? ((post-fn {:csrf-token-fn (fn [_] "tok")})
                          {:params {:csrf-token "tok"}}))))

         (testing "`:sente/skip-CSRF-check` => CSRF skipped"
           (is (reached? ((post-fn {:csrf-token-fn (constantly :sente/skip-CSRF-check)}) {}))))))))

#?(:clj
   (deftest test-async-ring-handlers
     (testing "Three-arity Ring handlers settle exactly once"
       (let [invoke
             (fn [handler ring-req]
               (let [responses_ (atom [])
                     errors_    (atom [])
                     returned   (handler ring-req #(swap! responses_ conj %) #(swap! errors_ conj %))]
                 {:returned returned, :responses @responses_, :errors @errors_}))

             opts {:csrf-token-fn (constantly :sente/skip-CSRF-check)}]

         (testing "a synchronously returned adapter response is submitted"
           (let [response {:status 202, :body ::returned}
                 adapter
                 (reify i/IServerChanAdapter
                   (ring-req->server-ch-resp [_ _ring-req _callbacks] response))
                 handler (:ajax-post-fn (sente/make-channel-socket-server! adapter opts))]

             (is (= {:returned nil, :responses [response], :errors []}
                   (invoke handler {})))
             (is (= response (handler {})))))

         (testing "an adapter callback wins over a returned response"
           (let [callback-response {:status 202, :body ::callback}
                 return-response   {:status 203, :body ::returned}
                 adapter
                 (reify i/IServerChanAdapter
                   (ring-req->server-ch-resp [_ _ring-req callbacks]
                     ((:ring-async-resp-fn callbacks) callback-response)
                     return-response))
                 handler (:ajax-post-fn (sente/make-channel-socket-server! adapter opts))]

             (is (= {:returned nil, :responses [callback-response], :errors []}
                   (invoke handler {})))))

         (testing "an immediate rejection is submitted"
           (let [response {:status 429, :body "slow down"}
                 server
                 (sente/make-channel-socket-server! stub-sch-adapter
                   (assoc opts :reject-fn (constantly response)))]

             (is (= {:returned nil, :responses [response], :errors []}
                   (invoke (:ajax-get-or-ws-handshake-fn server)
                     {:params {:client-id "client-1"}})))))

         (testing "a synchronous exception is raised"
           (let [server  (sente/make-channel-socket-server! stub-sch-adapter opts)
                 result  (invoke (:ajax-get-or-ws-handshake-fn server) {})
                 [error] (:errors result)]

             (is (nil? (:returned result)))
             (is (empty? (:responses result)))
             (is (instance? Throwable error))))))))

#?(:clj
   (deftest test-nil-lp-timeout
     (testing "A nil server long-poll timeout disables the timeout"
       (is
         (map?
           (sente/make-channel-socket-server! stub-sch-adapter
             {:lp-timeout-ms nil}))))))

#?(:clj
   (deftest test-sync-close-during-ws-handshake
     (testing "A synchronous close during the handshake doesn't leave a stale connection"
       (let [adapter
             (reify i/IServerChanAdapter
               (ring-req->server-ch-resp [_ _ring-req callbacks]
                 (let [open?_ (atom true)
                       sch
                       (reify i/IServerChan
                         (sch-open?  [_] @open?_)
                         (sch-close! [this]
                           (when (compare-and-set! open?_ true false)
                             ((:on-close callbacks) this true :closed)
                             true))
                         (sch-send! [this _websocket? _msg]
                           (when (compare-and-set! open?_ true false)
                             ((:on-close callbacks) this true :send-failed))
                           false))]

                   ((:on-open callbacks) sch true)
                   {:status 101})))

             server
             (sente/make-channel-socket-server! adapter
               {:csrf-token-fn (constantly :sente/skip-CSRF-check)
                :ws-kalive-ms nil
                :ms-allow-reconnect-before-close-ws 10})

             conns_ (get-in server [:private :conns_])]

         ((:ajax-get-or-ws-handshake-fn server)
          {:params {:client-id "client-1"}
           :session {:uid :user-1}})

         (is
           (loop [attempts 100]
             (cond
               (and (= {:ws {} :ajax {}} @conns_)
                    (= {:ws #{} :ajax #{} :any #{}} @(:connected-uids server)))
               true

               (pos? attempts)
               (do (Thread/sleep 10) (recur (dec attempts)))

               :else false)))))))

#?(:clj
   (deftest test-server-connection-lifecycle
     (testing "UID lifecycle spans clients and transport types"
       (let [connections_ (atom [])
             adapter
             (reify i/IServerChanAdapter
               (ring-req->server-ch-resp [_ ring-req callbacks]
                 (let [websocket? (:websocket? ring-req)
                       open?_     (atom true)
                       sch
                       (reify i/IServerChan
                         (sch-open? [_] @open?_)
                         (sch-close! [this]
                           (when (compare-and-set! open?_ true false)
                             ((:on-close callbacks) this websocket? :closed)
                             true))
                         (sch-send! [this _websocket? _msg]
                           ;; Ajax responses close their one-shot channel.
                           (when-not websocket? (i/sch-close! this))
                           true))
                       conn {:callbacks callbacks, :open?_ open?_
                             :sch sch, :websocket? websocket?}]
                   (swap! connections_ conj conn)
                   ((:on-open callbacks) sch websocket?)
                   {:status (if websocket? 101 200)})))

             server
             (sente/make-channel-socket-server! adapter
               {:csrf-token-fn (constantly :sente/skip-CSRF-check)
                :user-id-fn    (constantly ::user)
                :lp-timeout-ms nil, :ws-kalive-ms nil
                ;; Generous grace so a slow CI runner can't let the close
                ;; timer beat the immediate reconnect below
                :ms-allow-reconnect-before-close-ws   250
                :ms-allow-reconnect-before-close-ajax 250})
             handler (:ajax-get-or-ws-handshake-fn server)
             connect!
             (fn [client-id websocket? handshake?]
               (handler
                 {:websocket? websocket?
                  :params (cond-> {:client-id client-id}
                            handshake? (assoc :handshake? true))})
               (peek @connections_))
             close! (fn [{:keys [sch]}] (i/sch-close! sch))]

         (let [ws1 (connect! "client-1" true false)]
           (close! ws1)
           (let [ws1-again (connect! "client-1" true false)]
             ;; The first close is superseded by its reconnect during grace.
             (Thread/sleep 500)
             (is (= {:ws #{::user} :ajax #{} :any #{::user}}
                   @(:connected-uids server)))

             (let [ws2 (connect! "client-2" true false)
                   _   (connect! "client-3" false true)
                   ajax (connect! "client-3" false false)]
               (is (= {:ws #{::user} :ajax #{::user} :any #{::user}}
                     @(:connected-uids server)))

               (doseq [conn [ws1-again ws2 ajax]] (close! conn))
               (is (await-pred #(= {:ws #{} :ajax #{} :any #{}}
                                  @(:connected-uids server))))

               (let [events
                     (loop [events []]
                       (if-let [event-msg (async/poll! (:ch-recv server))]
                         (recur (conj events (:event event-msg)))
                         events))]
                 (is (= [[:chsk/uidport-open ::user]
                         [:chsk/uidport-close ::user]]
                       events))))))))))

#?(:clj
   (deftest test-server-push-fan-out
     (testing "Server pushes batch in order and fan out only to the requested UID"
       (let [connections_ (atom [])
             adapter      (recording-sch-adapter connections_)
             packer       (ep/get-packer)
             server
             (sente/make-channel-socket-server! adapter
               {:csrf-token-fn (constantly :sente/skip-CSRF-check)
                :user-id-fn    (fn [ring-req & _] (get-in ring-req [:session :uid]))
                :packer packer, :ws-kalive-ms nil
                :send-buf-ms-ws 60000, :send-buf-ms-ajax 60000})
             connect!
             (fn [uid client-id]
               ((:ajax-get-or-ws-handshake-fn server)
                {:websocket? true
                 :params {:client-id client-id}
                 :session {:uid uid}})
               (peek @connections_))
             conn-a1 (connect! ::user-a "a1")
             conn-a2 (connect! ::user-a "a2")
             conn-b  (connect! ::user-b "b1")]

         (doseq [conn @connections_]
           (reset! (:attempts_ conn) [])
           (reset! (:sent_ conn) []))

         (reset! (:send?_ conn-a2) false)
         ((:send-fn server) ::user-a [::push 1])
         ((:send-fn server) ::user-a [::push 2] {:flush? true})

         (is (= [[[::push 1] [::push 2]] nil]
               (unpack-sync packer true (first @(:sent_ conn-a1)))))
         (is (seq @(:attempts_ conn-a2)))
         (is (empty? @(:sent_ conn-a2)))
         (is (empty? @(:attempts_ conn-b)))))))

#?(:clj
   (deftest test-server-ajax-flow
     (doseq [[packer-id packer binary?]
             [[:text   (ep/get-packer) false]
              [:binary (mp/get-packer) true]]]
       (testing (str "Ajax handshake, poll, push, and POST with " (name packer-id))
         (let [uid          ::ajax-user
               connections_ (atom [])
               server
               (sente/make-channel-socket-server!
                 (recording-sch-adapter connections_)
                 {:csrf-token-fn (constantly :sente/skip-CSRF-check)
                  :user-id-fn    (constantly uid)
                  :packer packer, :lp-timeout-ms nil
                  :send-buf-ms-ajax 60000, :send-buf-ms-ws 60000})
               get-handler  (:ajax-get-or-ws-handshake-fn server)
               post-handler (:ajax-post-fn server)
               request
               (fn [params]
                 {:params (merge {:client-id "client-1"} params)})
               post!
               (fn [event has-cb?]
                 (let [packed (pack-sync packer false [event has-cb?])]
                   (post-handler
                     (if binary?
                       (assoc (request {:binary? true})
                         :body ::legacy-express-request
                         ::sente/ajax-body
                         (java.io.ByteArrayInputStream. ^bytes packed))
                       (request {:ppstr packed})))
                   (peek @connections_)))
               take-event!
               (fn [expected]
                 (loop []
                   (let [[event-msg ch]
                         (async/alts!! [(:ch-recv server)
                                        (async/timeout *timeout-msecs*)])]
                     (cond
                       (nil? event-msg) :timeout
                       (= (:event event-msg) expected) event-msg
                       :else (recur)))))]

           (get-handler (request {:handshake? true}))
           (let [handshake-conn (first @connections_)]
             (is (= [[:chsk/handshake [uid nil nil]] nil]
                   (unpack-sync packer false (first @(:sent_ handshake-conn))))))

           (get-handler (request {}))
           (let [poll-conn (second @connections_)]
             (is @(:open?_ poll-conn))
             (is (empty? @(:sent_ poll-conn)))

             ((:send-fn server) uid [::push 1] {:flush? true})
             (is (= [[[::push 1]] nil]
                   (unpack-sync packer false (first @(:sent_ poll-conn))))))

           (let [post-conn (post! [::request 1] true)
                 event-msg (take-event! [::request 1])]
             (is (fn? (:?reply-fn event-msg)))
             ((:?reply-fn event-msg) {:answer 2})
             (is (= [{:answer 2} nil]
                   (unpack-sync packer false (first @(:sent_ post-conn))))))

           (let [post-conn (post! [::notify 3] false)
                 event-msg (take-event! [::notify 3])]
             (is (nil? (:?reply-fn event-msg)))
             (is (= [:chsk/dummy-cb-200 nil]
                   (unpack-sync packer false (first @(:sent_ post-conn)))))))))))

#?(:clj
   (deftest test-java-ws-binary-message
     (testing "The Java WebSocket client forwards binary frames as independent byte arrays"
       (let [received_ (atom nil)
             ws-client
             (#'java-ws/make-ws-client
              "ws://localhost/test" nil
              (fn [_]) #(reset! received_ %) (fn [_ _ _]))

             source (byte-array [0 1 2 3])
             bb     (doto (java.nio.ByteBuffer/wrap source) (.position 1))]

         (.onMessage ws-client bb)
         (aset-byte source 1 (byte 9))

         (is (= [1 2 3] (vec @received_)))
         (is (= 1 (.position bb)))))))

#?(:clj
   (deftest test-client-callback-lifecycle
     (testing "Callbacks settle once across closed, reply, timeout, and error outcomes"
       (let [packer     (ep/get-packer)
             callbacks_ (atom [])
             sent_      (atom [])
             send-error?_ (atom false)
             ws-constructor
             (fn [callbacks]
               (swap! callbacks_ conj callbacks)
               (delay
                 (reify i/IClientWebSocket
                   (cws-raw [_] nil)
                   (cws-send [_ data]
                     (if @send-error?_
                       (do
                         (swap! sent_ conj data)
                         (throw (ex-info "send failed" {})))
                       (do (swap! sent_ conj data) true)))
                   (cws-close [_ _code _reason _clean?] true))))
             client
             (sente/make-channel-socket-client! "/chsk" nil
               {:type :ws, :protocol :http, :host "localhost"
                :packer packer, :ws-constructor ws-constructor
                :ws-kalive-ms nil})
             chsk (:chsk client)
             callback-id
             (fn [packed]
               (second (unpack-sync packer true packed)))]
         (try
           (let [closed_ (atom [])]
             (is (false? (sente/chsk-send! chsk [::closed] 100 #(swap! closed_ conj %))))
             (is (= [:chsk/closed] @closed_)))

           ((:on-message (first @callbacks_))
            (pack-sync packer true [[:chsk/handshake [::user nil nil]] nil]))

           (let [replies_ (atom [])]
             (sente/chsk-send! chsk [::request] 1000 #(swap! replies_ conj %))
             (let [cb-uuid (callback-id (peek @sent_))
                   reply   (pack-sync packer true [::reply cb-uuid])]
               ((:on-message (first @callbacks_)) reply)
               ((:on-message (first @callbacks_)) reply)
               (is (= [::reply] @replies_))))

           (let [replies_ (atom [])]
             (sente/chsk-send! chsk [::request] 25 #(swap! replies_ conj %))
             (let [cb-uuid (callback-id (peek @sent_))]
               (is (await-pred #(= [:chsk/timeout] @replies_)))
               ((:on-message (first @callbacks_))
                (pack-sync packer true [::late-reply cb-uuid]))
               (is (= [:chsk/timeout] @replies_))))

           (let [replies_ (atom [])]
             (reset! send-error?_ true)
             (sente/chsk-send! chsk [::request] 25 #(swap! replies_ conj %))
             (is (await-pred #(= [:chsk/error] @replies_)))
             ((:on-message (peek @callbacks_))
              (pack-sync packer true
                [::late-reply (callback-id (peek @sent_))]))
             (is (= [:chsk/error] @replies_)))

           (finally
             (sente/chsk-disconnect! chsk)))))))

#?(:clj
   (deftest test-websocket-reconnect-lifecycle
     (testing "Unexpected closes retry, while manual disconnects do not"
       (let [packer     (ep/get-packer)
             callbacks_ (atom [])
             closes_    (atom [])
             ws-constructor
             (fn [callbacks]
               (let [idx (count @callbacks_)]
                 (swap! callbacks_ conj callbacks)
                 (delay
                   (reify i/IClientWebSocket
                     (cws-raw  [_] nil)
                     (cws-send [_ _data] true)
                     (cws-close [_ code reason _clean?]
                       (swap! closes_ conj [idx code reason])
                       ((:on-close (nth @callbacks_ idx)) code reason false)
                       true)))))
             client
             (sente/make-channel-socket-client! "/chsk" nil
               {:type :ws, :protocol :http, :host "localhost"
                :packer packer, :ws-constructor ws-constructor
                :ws-kalive-ms nil})
             chsk   (:chsk client)
             state_ (:state client)
             handshake
             (pack-sync packer true
               [[:chsk/handshake [::user nil nil]] nil])]
         (try
           ((:on-message (first @callbacks_)) handshake)
           ((:on-close (first @callbacks_)) 1006 "connection lost" true)

           (is (= 2 (count @callbacks_)))
           (is (= {:open? false, :reason :unexpected}
                 {:open? (:open? @state_)
                  :reason (get-in @state_ [:last-close :reason])}))
           (is (= {:code 1006, :reason "connection lost", :clean? false}
                 (dissoc (:last-ws-close @state_) :udt)))

           ((:on-message (second @callbacks_)) handshake)
           (sente/chsk-disconnect! chsk)
           (is (= 2 (count @callbacks_)))
           (is (= :requested-disconnect
                 (get-in @state_ [:last-close :reason])))
           (is (some #{[1 1000 "CLOSE_NORMAL"]} @closes_))

           (finally
             (sente/chsk-disconnect! chsk)))))))

#?(:clj
   (deftest test-websocket-keepalive
     (doseq [[reply expected-reason]
             [["pong" nil]
              [::wrong-pong :ws-ping-timeout]
              [::no-reply   :ws-ping-timeout]]]
       (testing (if expected-reason
                  "A missed or wrong pong reconnects"
                  "A pong keeps the connection open")
         (let [packer      (ep/get-packer)
               callbacks_ (atom [])
               ping-seen  (promise)
               ws-constructor
               (fn [callbacks]
                 (let [idx (count @callbacks_)]
                   (swap! callbacks_ conj callbacks)
                   (delay
                     (reify i/IClientWebSocket
                       (cws-raw [_] nil)
                       (cws-send [_ data]
                         (let [[event cb-uuid]
                               (unpack-sync packer true data)]
                           (when (= event [:chsk/ws-ping])
                             (deliver ping-seen true)
                             (when-not (= reply ::no-reply)
                               ((:on-message (nth @callbacks_ idx))
                                (pack-sync packer true [reply cb-uuid])))))
                         true)
                       (cws-close [_ _code _reason _clean?] true)))))
               client
               (sente/make-channel-socket-client! "/chsk" nil
                 {:type :ws, :protocol :http, :host "localhost"
                  :packer packer, :ws-constructor ws-constructor
                  :ws-kalive-ms 50, :ws-ping-timeout-ms 100})
               chsk   (:chsk client)
               state_ (:state client)]
           (try
             ((:on-message (first @callbacks_))
              (pack-sync packer true
                [[:chsk/handshake [::user nil nil]] nil]))
             (is (= true (deref ping-seen *timeout-msecs* :timeout)))

             (if expected-reason
               (do
                 (is (await-pred #(<= 2 (count @callbacks_))))
                 (is (= expected-reason
                       (get-in @state_ [:last-close :reason]))))
               (do
                 (is (= 1 (count @callbacks_)))
                 (is (:open? @state_))))

             (finally
               (sente/chsk-disconnect! chsk))))))))

#?(:clj
   (deftest test-client-packer-errors
     (testing "Pack and unpack failures propagate without wedging later messages"
       (let [base-packer   (ep/get-packer)
             async-pack_  (atom nil)
             async-unpack_ (atom nil)
             packer
             (reify i/IPacker2
               (pack [_ ws? value cb-fn]
                 (case (ffirst value)
                   ::sync-pack  (throw (ex-info "sync pack" {}))
                   ::async-pack (reset! async-pack_ cb-fn)
                   (i/pack base-packer ws? value cb-fn)))
               (unpack [_ ws? packed cb-fn]
                 (case packed
                   ::sync-unpack  (throw (ex-info "sync unpack" {}))
                   ::async-unpack (reset! async-unpack_ cb-fn)
                   (i/unpack base-packer ws? packed cb-fn))))
             callbacks_ (atom nil)
             sent_      (atom [])
             ws-constructor
             (fn [callbacks]
               (reset! callbacks_ callbacks)
               (delay
                 (reify i/IClientWebSocket
                   (cws-raw   [_] nil)
                   (cws-send  [_ data] (swap! sent_ conj data) true)
                   (cws-close [_ _code _reason _clean?] true))))
             client
             (sente/make-channel-socket-client! "/chsk" nil
               {:type :ws, :protocol :http, :host "localhost"
                :packer packer, :ws-constructor ws-constructor
                :ws-kalive-ms nil})
             chsk    (:chsk client)
             recv-ch (:ch-recv client)
             receive-event!
             (fn [expected]
               (loop [timeout-ch (async/timeout *timeout-msecs*)]
                 (let [[event-msg ch] (async/alts!! [recv-ch timeout-ch])]
                   (cond
                     (= ch timeout-ch) :timeout
                     (= (:event event-msg) expected) expected
                     :else (recur timeout-ch)))))]
         (try
           ((:on-message @callbacks_)
            (pack-sync base-packer true
              [[:chsk/handshake [::user nil nil]] nil]))

           (is (thrown? Exception
                 (sente/chsk-send! chsk [::sync-pack])))
           (sente/chsk-send! chsk [::after-sync-pack])
           (is (= 1 (count @sent_)))

           (sente/chsk-send! chsk [::async-pack])
           (sente/chsk-send! chsk [::after-async-pack])
           (is (= 1 (count @sent_)))
           (is (thrown? Exception
                 (@async-pack_ {:error (ex-info "async pack" {})})))
           (is (= 2 (count @sent_)))
           (is (nil? (@async-pack_ {:value ::duplicate-result})))
           (is (= 2 (count @sent_)))

           (is (thrown? Exception
                 ((:on-message @callbacks_) ::sync-unpack)))
           ((:on-message @callbacks_)
            (pack-sync base-packer true [[[::after-sync-unpack]] nil]))
           (is (= [::after-sync-unpack]
                 (receive-event! [::after-sync-unpack])))

           ((:on-message @callbacks_) ::async-unpack)
           ((:on-message @callbacks_)
            (pack-sync base-packer true [[[::after-async-unpack]] nil]))
           (is (thrown? Exception
                 (@async-unpack_ {:error (ex-info "async unpack" {})})))
           (is (= [::after-async-unpack]
                 (receive-event! [::after-async-unpack])))
           (is (nil? (@async-unpack_ {:value [[[::duplicate]] nil]})))

           (finally
             (sente/chsk-disconnect! chsk)))))))

#?(:cljs
   (deftest test-ajax-client-flow
     (testing "Ajax handshakes, repolls, sends, and aborts deterministically"
       (let [packer   (ep/get-packer)
             calls_   (atom [])
             aborted_ (atom [])
             ajax-call
             (fn [_url opts cb-fn]
               (let [call-idx (count @calls_)
                     xhr      #js {}]
                 (aset xhr "abort" #(swap! aborted_ conj call-idx))
                 (swap! calls_ conj {:opts opts, :cb-fn cb-fn, :xhr xhr})
                 (when-let [xhr-cb-fn (:xhr-cb-fn opts)] (xhr-cb-fn xhr))
                 xhr))
             respond!
             (fn [idx response]
               ((:cb-fn (nth @calls_ idx)) response))
             packed
             (fn [value]
               (pack-now packer false value))]

         (with-redefs [sente/ajax-call ajax-call]
           (let [client
                 (sente/make-channel-socket-client! "/chsk" "token"
                   {:type :ajax, :protocol :http, :host "localhost"
                    :legacy-ws-csrf-token-in-url? false
                    :client-id "client-1", :packer packer})
                 chsk    (:chsk client)
                 state_  (:state client)]
             (try
               (is (= :get (get-in @calls_ [0 :opts :method])))
               (is (= 1 (get-in @calls_ [0 :opts :params :handshake?])))

               (respond! 0
                 {:?content
                  (packed [[:chsk/handshake [::user nil {:welcome true}]] nil])})
               (is (= {:type :ajax, :open? true, :uid ::user
                       :handshake-data {:welcome true}}
                     (select-keys @state_
                       [:type :open? :uid :handshake-data])))
               (is (= :get (get-in @calls_ [1 :opts :method])))
               (is (nil? (get-in @calls_ [1 :opts :params :handshake?])))

               (let [replies_ (atom [])]
                 ((:send-fn client) [::request 1] 1000
                   #(swap! replies_ conj %))
                 (is (= :post (get-in @calls_ [2 :opts :method])))
                 (is (= "token" (get-in @calls_ [2 :opts :headers "X-CSRF-Token"])))
                 (respond! 2 {:?content (packed [::reply nil])})
                 (is (= [::reply] @replies_)))

               (let [replies_ (atom [])]
                 ((:send-fn client) [::request 2] 1000
                   #(swap! replies_ conj %))
                 (respond! 3 {:?error :timeout})
                 (is (= [:chsk/timeout] @replies_))
                 (is (:open? @state_)))

               (let [replies_ (atom [])]
                 ((:send-fn client) [::request 3] 1000
                   #(swap! replies_ conj %))
                 (respond! 4 {:?error :network})
                 (is (= [:chsk/error] @replies_))
                 (is (= :unexpected (get-in @state_ [:last-close :reason]))))

               (sente/chsk-disconnect! chsk)
               (is (= [1] @aborted_))

               (finally
                 (sente/chsk-disconnect! chsk)))))))))

#?(:cljs
   (deftest test-auto-client-ajax-fallback
     (testing "An initial WebSocket error downgrades :auto to Ajax"
       (let [ws-callbacks_ (atom nil)
             ws-closed_    (atom 0)
             ajax-calls_   (atom [])
             ws-constructor
             (fn [callbacks]
               (reset! ws-callbacks_ callbacks)
               (delay
                 (reify i/IClientWebSocket
                   (cws-raw   [_] nil)
                   (cws-send  [_ _] true)
                   (cws-close [_ _ _ _] (swap! ws-closed_ inc)))))
             ajax-call
             (fn [_url opts cb-fn]
               (let [xhr #js {}]
                 (aset xhr "abort" (fn []))
                 (swap! ajax-calls_ conj {:opts opts, :cb-fn cb-fn})
                 xhr))]

         (with-redefs [sente/ajax-call ajax-call]
           (let [client
                 (sente/make-channel-socket-client! "/chsk" nil
                   {:type :auto, :protocol :http, :host "localhost"
                    :client-id "client-1"
                    :ws-constructor ws-constructor
                    :ws-kalive-ms nil})]
             (try
               ((:on-error @ws-callbacks_) #js {})
               (is (= 1 @ws-closed_))
               (is (= :get (get-in @ajax-calls_ [0 :opts :method])))
               (is (= 1 (get-in @ajax-calls_ [0 :opts :params :handshake?])))
               ((:cb-fn (first @ajax-calls_))
                {:?content
                 (pack-now (ep/get-packer) false
                   [[:chsk/handshake [::user nil nil]] nil])})
               (is (= {:type :ajax, :open? true, :uid ::user}
                     (select-keys @(:state client) [:type :open? :uid])))
               (finally
                 (sente/chsk-disconnect! (:chsk client))))))))))

#?(:clj
   (deftest test-websocket-roundtrip
     (doseq [[packer-id packer] [[:edn     (ep/get-packer)]
                                 [:msgpack (mp/get-packer)]]]
       (testing (str "Default query-token WebSocket roundtrip with " (name packer-id))
         (let [uid              ::test-user
               server-received (promise)
               client-reply    (promise)
               server
               (sente/make-channel-socket-server!
                 (http-kit/get-sch-adapter)
                 {:csrf-token-fn (constantly "secret-token")
                  :user-id-fn    (constantly uid)
                  :packer packer, :ws-kalive-ms nil})
               stop-router!
               (sente/start-server-chsk-router!
                 (:ch-recv server)
                 (fn [{:keys [event ?reply-fn]}]
                   (when (= event [::request {:value 1}])
                     (deliver server-received event)
                     (?reply-fn {:value 2}))))
               ring-handler
               (ring-params/wrap-params
                 (ring-keyword-params/wrap-keyword-params
                   (fn [ring-req]
                     (if (= (:uri ring-req) "/chsk")
                       ((:ajax-get-or-ws-handshake-fn server) ring-req)
                       {:status 404}))))
               http-server
               (hk/run-server ring-handler {:port 0, :legacy-return-value? false})
               client
               (sente/make-channel-socket-client!
                 "/chsk" "secret-token"
                 {:type :ws, :protocol :http, :host "localhost"
                  :port (hk/server-port http-server)
                  :packer packer
                  :ws-constructor java-ws/make-client-ws
                  :ws-kalive-ms nil})]
           (try
             (let [opened? (await-pred #(:open? @(:state client)))]
               (is opened? "Client completed the WebSocket handshake")

               (when opened?
                 ((:send-fn client) [::request {:value 1}] *timeout-msecs*
                  #(deliver client-reply %))
                 (is (= [::request {:value 1}]
                       (deref server-received *timeout-msecs* :timeout)))
                 (is (= {:value 2}
                       (deref client-reply *timeout-msecs* :timeout)))

                 ((:send-fn server) uid [::push {:value 3}])
                 (is (= [::push {:value 3}]
                       (loop [timeout-ch (async/timeout *timeout-msecs*)]
                         (let [[event-msg ch]
                               (async/alts!! [(:ch-recv client) timeout-ch])]
                           (cond
                             (= ch timeout-ch) :timeout
                             (= (:event event-msg) [::push {:value 3}]) (:event event-msg)
                             :else (recur timeout-ch))))))))

             (finally
               (sente/chsk-disconnect! (:chsk client))
               (stop-router!)
               (hk/server-stop! http-server {:timeout 100}))))))))

(deftest test-websocket-client-csrf-transport
  (let [capture-opts
        (fn [opts]
          (let [constructor-opts_ (atom nil)
                ws-constructor
                (fn [opts]
                  (reset! constructor-opts_ opts)
                  (delay
                    (reify i/IClientWebSocket
                      (cws-raw   [_] nil)
                      (cws-send  [_ _data] true)
                      (cws-close [_ _code _reason _clean?] true))))
                client
                (sente/make-channel-socket-client! "/chsk" "secret/tok"
                  (merge
                    {:type :ws, :protocol :http, :host "localhost"
                     :headers {"X-App" "yes"}
                     :ws-constructor ws-constructor
                     :ws-kalive-ms nil}
                    opts))]
            [@constructor-opts_ client]))]

    (testing "Opt-in secure WebSocket CSRF transport"
      (let [[{:keys [uri-str headers protocols] :as constructor-opts} client]
            (capture-opts {:legacy-ws-csrf-token-in-url? false})]
        (try
          (is (not (str/includes? uri-str "csrf")))
          (is (= "secret/tok" (get headers "X-CSRF-Token")))
          (is (= "yes" (get headers "X-App")))
          (is (= #?(:clj  nil
                   :cljs ["sente-csrf-v1.secret%2Ftok"])
                protocols))
          (is (true? (:taoensso.sente/use-ws-protocols? constructor-opts)))
          (finally
            (sente/chsk-disconnect! (:chsk client))))))

    (testing "Default legacy WebSocket CSRF transport"
      (let [[{:keys [uri-str headers] :as constructor-opts} client]
            (capture-opts nil)]
        (try
          (is (str/includes? uri-str "csrf-token=secret%2Ftok"))
          (is (nil? (get headers "X-CSRF-Token")))
          (is (= "yes" (get headers "X-App")))
          (is (not (contains? constructor-opts :protocols)))
          (is (not (contains? constructor-opts
                     :taoensso.sente/use-ws-protocols?)))
          (finally
            (sente/chsk-disconnect! (:chsk client))))))

    (testing "Legacy transport preserves custom constructor protocols"
      (let [[{:keys [protocols] :as constructor-opts} client]
            (capture-opts {:ws-opts {:protocols "chat"}})]
        (try
          (is (= "chat" protocols))
          (is (not (contains? constructor-opts
                     :taoensso.sente/use-ws-protocols?)))
          (finally
            (sente/chsk-disconnect! (:chsk client))))))))

#?(:clj
   (deftest test-websocket-server-csrf-transport
     (testing "The server selects the first requested subprotocol"
       (let [callbacks_ (atom nil)
             adapter
             (reify i/IServerChanAdapter
               (ring-req->server-ch-resp [_ _ring-req callbacks]
                 (reset! callbacks_ callbacks)
                 {:status 101}))
             server
             (sente/make-channel-socket-server! adapter
               {:csrf-token-fn (constantly "secret/tok")})]
         ((:ajax-get-or-ws-handshake-fn server)
          {:params {:client-id "client-1"}
           :headers {"sec-websocket-protocol"
                     "chat, sente-csrf-v1.secret%2Ftok"}})
         (is (= "chat" (:ws-protocol @callbacks_)))))

     (testing "The http-kit adapter returns the selected subprotocol"
       (let [server
             (sente/make-channel-socket-server!
               (http-kit/get-sch-adapter)
               {:csrf-token-fn (constantly "secret-token")
                :ws-kalive-ms nil})
             ring-handler
             (ring-params/wrap-params
               (ring-keyword-params/wrap-keyword-params
                 (:ajax-get-or-ws-handshake-fn server)))
             http-server
             (hk/run-server ring-handler {:port 0, :legacy-return-value? false})]
         (try
           (with-open [socket (java.net.Socket. "localhost" (int (hk/server-port http-server)))
                       writer (java.io.BufferedWriter.
                                (java.io.OutputStreamWriter. (.getOutputStream socket)))
                       reader (java.io.BufferedReader.
                                (java.io.InputStreamReader. (.getInputStream socket)))]
             (doseq [line
                     ["GET /chsk?client-id=client-1 HTTP/1.1"
                      "Host: localhost"
                      "Upgrade: websocket"
                      "Connection: Upgrade"
                      "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
                      "Sec-WebSocket-Version: 13"
                      "Sec-WebSocket-Protocol: chat, sente-csrf-v1.secret-token"
                      ""]]
               (.write writer (str line "\r\n")))
             (.flush writer)
             (let [headers (doall (take-while seq (line-seq reader)))]
               (is (= "HTTP/1.1 101 Switching Protocols" (first headers)))
               (is (some #{"sec-websocket-protocol: chat"}
                     (map str/lower-case headers)))))
           (finally
             (hk/server-stop! http-server {:timeout 100})))))))

#?(:clj
   (deftest test-jetty-ajax-binary-response
     (testing "The Jetty Ajax adapter writes text and binary responses"
       (let [write-response
             (fn [response]
               (let [ch
                     (jetty/->SenteJettyAjaxChannel
                       {} (promise) (atom true) nil nil)
                     out (java.io.ByteArrayOutputStream.)]

                 (i/sch-send! ch false response)
                 @(ring-protocols/write-body-to-stream ch nil out)
                 (.toByteArray out)))]

         (is (= [0 1 -1]
               (vec (write-response (byte-array [0 1 -1])))))
         (is (= "text"
               (String. ^bytes (write-response "text")
                 java.nio.charset.StandardCharsets/UTF_8)))))))

#?(:clj
   (deftest test-superseded-server-websocket-messages
     (testing "A superseded server WebSocket cannot deliver messages"
       (let [base-packer         (ep/get-packer)
             deferred-unpack-cb_ (atom nil)
             packer
             (reify i/IPacker2
               (pack [_ ws? clj cb-fn]
                 (i/pack base-packer ws? clj cb-fn))
               (unpack [_ ws? packed cb-fn]
                 (if (= packed ::deferred-message)
                   (reset! deferred-unpack-cb_ cb-fn)
                   (i/unpack base-packer ws? packed cb-fn))))

             conns_ (atom [])
             adapter
             (reify i/IServerChanAdapter
               (ring-req->server-ch-resp [_ _ring-req callbacks]
                 (let [sch
                       (reify i/IServerChan
                         (sch-open?  [_] true)
                         (sch-close! [_] true)
                         (sch-send!  [_ _websocket? _msg] true))]
                   (swap! conns_ conj [callbacks sch])
                   ((:on-open callbacks) sch true)
                   {:status 101})))

             server
             (sente/make-channel-socket-server! adapter
               {:csrf-token-fn (constantly :sente/skip-CSRF-check)
                :packer packer, :ws-kalive-ms nil})
             handler (:ajax-get-or-ws-handshake-fn server)
             request {:params {:client-id "client-1"}
                      :session {:uid :user-1}}
             pack
             (fn [value]
               (let [packed_ (atom nil)]
                 (i/pack base-packer true value #(reset! packed_ (:value %)))
                 @packed_))]

         (handler request)
         (let [[old-callbacks old-sch] (first @conns_)]
           ((:on-msg old-callbacks) old-sch true ::deferred-message)
           (is (some? @deferred-unpack-cb_))

           (handler request)
           (let [[new-callbacks new-sch] (second @conns_)]
             (@deferred-unpack-cb_ {:value [[::stale-async 1] nil]})
             ((:on-msg old-callbacks) old-sch true
              (pack [[::stale-direct 2] nil]))
             ((:on-msg new-callbacks) new-sch true
              (pack [[::current 3] nil])))

           (let [timeout-ch (async/timeout 200)
                 events
                 (loop [events []]
                   (let [[event-msg ch]
                         (async/alts!! [(:ch-recv server) timeout-ch])]
                     (if (= ch timeout-ch)
                       (set events)
                       (recur (conj events (:event event-msg))))))]
             (is (contains? events [::current 3]))
             (is (not (contains? events [::stale-async 1])))
             (is (not (contains? events [::stale-direct 2])))))))))

#?(:clj
   (deftest test-superseded-websocket-messages
     (testing "A superseded WebSocket cannot send or deliver delayed messages"
       (let [base-packer         (ep/get-packer)
             deferred-pack-cb_   (atom nil)
             deferred-unpack-cb_ (atom nil)
             packer
             (reify i/IPacker2
               (pack [_ ws? clj cb-fn]
                 (if (= (ffirst clj) ::stale-send)
                   (reset! deferred-pack-cb_ cb-fn)
                   (i/pack base-packer ws? clj cb-fn)))
               (unpack [_ ws? packed cb-fn]
                 (if (= packed ::deferred-message)
                   (reset! deferred-unpack-cb_ cb-fn)
                   (i/unpack base-packer ws? packed cb-fn))))

             callbacks_ (atom [])
             sent_      (atom [])
             ws-constructor
             (fn [callbacks]
               (let [idx (count @callbacks_)]
                 (swap! callbacks_ conj callbacks)
                 (swap! sent_ conj [])
                 (delay
                   (reify i/IClientWebSocket
                     (cws-raw   [_] nil)
                     (cws-send  [_ data] (swap! sent_ update idx conj data) true)
                     (cws-close [_ _code _reason _clean?] true)))))

             client
             (sente/make-channel-socket-client! "/chsk" nil
               {:type :ws, :protocol :http, :host "localhost"
                :packer packer, :ws-constructor ws-constructor
                :ws-kalive-ms nil})

             chsk      (:chsk client)
             state_    (:state client)
             recv-ch   (:ch-recv client)
             stale-send-result_ (atom nil)
             pack
             (fn [value]
               (let [packed_ (atom nil)]
                 (i/pack base-packer true value #(reset! packed_ (:value %)))
                 @packed_))]

         (try
           (let [old-callbacks (first @callbacks_)]
             ((:on-message old-callbacks)
              (pack [[:chsk/handshake [:user-1 nil nil]] nil]))
             (is (:open? @state_))

             ;; Begin unpacking while the old socket still owns the connection.
             ((:on-message old-callbacks) ::deferred-message)
             (is (some? @deferred-unpack-cb_))

             (sente/chsk-send! chsk [::stale-send 4] 1000
               #(reset! stale-send-result_ %))
             (is (some? @deferred-pack-cb_))

             (sente/chsk-reconnect! chsk)
             (is (= 2 (count @callbacks_)))
             (let [new-callbacks (second @callbacks_)]
               ((:on-message new-callbacks)
                (pack [[:chsk/handshake [:user-1 nil nil]] nil]))
               (is (:open? @state_)))

             ;; Complete that unpack after replacement, and also deliver a new
             ;; message directly through the old socket callback.
             (@deferred-unpack-cb_ {:value [[[::stale-async 1]] nil]})
             ((:on-message old-callbacks)
              (pack [[[::stale-direct 2]] nil]))
             (@deferred-pack-cb_ {:value ::stale-packed})

             ;; The current socket should remain able to deliver normally.
             ((:on-message (second @callbacks_))
              (pack [[[::current 3]] nil]))

             (let [timeout-ch (async/timeout 200)
                   events
                   (loop [events []]
                     (let [[event-msg ch] (async/alts!! [recv-ch timeout-ch])]
                       (if (= ch timeout-ch)
                         events
                         (recur (conj events (:event event-msg))))))

                   events (set events)]

               (is (contains? events [::current 3]))
               (is (not (contains? events [::stale-async 1])))
               (is (not (contains? events [::stale-direct 2])))
               (is (= :chsk/closed @stale-send-result_))
               (is (every? empty? @sent_))))

           (finally
             (sente/chsk-disconnect! chsk)))))))

#?(:clj
   (deftest test-async-packer-ordering
     (testing "Async pack and unpack completion cannot reorder WebSocket messages"
       (let [base-packer  (ep/get-packer)
             pack-cbs_    (atom {})
             unpack-cbs_  (atom {})
             packer
             (reify i/IPacker2
               (pack [_ ws? value cb-fn]
                 (let [event-id (ffirst value)]
                   (if (#{::send-first ::send-second} event-id)
                     (swap! pack-cbs_ assoc event-id cb-fn)
                     (i/pack base-packer ws? value cb-fn))))
               (unpack [_ ws? packed cb-fn]
                 (if (#{::wire-first ::wire-second} packed)
                   (swap! unpack-cbs_ assoc packed cb-fn)
                   (i/unpack base-packer ws? packed cb-fn))))

             sent_      (atom [])
             callbacks_ (atom nil)
             ws-constructor
             (fn [callbacks]
               (reset! callbacks_ callbacks)
               (delay
                 (reify i/IClientWebSocket
                   (cws-raw   [_] nil)
                   (cws-send  [_ data] (swap! sent_ conj data) true)
                   (cws-close [_ _code _reason _clean?] true))))

             client
             (sente/make-channel-socket-client! "/chsk" nil
               {:type :ws, :protocol :http, :host "localhost"
                :packer packer, :ws-constructor ws-constructor
                :ws-kalive-ms nil})

             chsk    (:chsk client)
             recv-ch (:ch-recv client)
             pack
             (fn [value]
               (let [packed_ (atom nil)]
                 (i/pack base-packer true value #(reset! packed_ (:value %)))
                 @packed_))

             receive-event? (fn [[id]] (contains? #{::receive-first ::receive-second} id))
             drain-events!
             (fn []
               ;; Return pending `::receive-*` events, ignoring
               ;; handshake/state events that may arrive late under CI load
               (let [timeout-ch (async/timeout 25)]
                 (loop [events []]
                   (let [[event-msg ch] (async/alts!! [recv-ch timeout-ch])]
                     (if (= ch timeout-ch)
                       events
                       (recur (cond-> events
                                (receive-event? (:event event-msg))
                                (conj (:event event-msg)))))))))]

         (try
           ((:on-message @callbacks_)
            (pack [[:chsk/handshake [:user-1 nil nil]] nil]))
           (drain-events!) ; Discard handshake and state notifications.

           (sente/chsk-send! chsk [::send-first 1])
           (sente/chsk-send! chsk [::send-second 2])
           ((get @pack-cbs_ ::send-second) {:value ::packed-second})
           (is (empty? @sent_))
           ((get @pack-cbs_ ::send-first) {:value ::packed-first})
           (is (= [::packed-first ::packed-second] @sent_))

           ((:on-message @callbacks_) ::wire-first)
           ((:on-message @callbacks_) ::wire-second)
           ((get @unpack-cbs_ ::wire-second)
            {:value [[[::receive-second 2]] nil]})
           (is (empty? (drain-events!)))
           ((get @unpack-cbs_ ::wire-first)
            {:value [[[::receive-first 1]] nil]})

           (let [timeout-ch (async/timeout *timeout-msecs*)
                 events
                 (loop [events []]
                   (if (= 2 (count events))
                     events
                     (let [[event-msg ch] (async/alts!! [recv-ch timeout-ch])]
                       (if (= ch timeout-ch)
                         events
                         (recur (cond-> events
                                  (receive-event? (:event event-msg))
                                  (conj (:event event-msg))))))))]
             (is (= [[::receive-first 1] [::receive-second 2]] events)))

           (finally
             (sente/chsk-disconnect! chsk)))))))

#?(:clj
   (deftest test-server-router-contract
     (testing "The default router is ordered and survives handler errors"
       (let [ch        (async/chan 8)
             processed_ (atom [])
             errors_    (atom [])
             done       (promise)
             event-msg
             (fn [event]
               {:ch-recv ch, :send-fn (fn [& _])
                :connected-uids (atom {}), :send-buffers (atom {})
                :ring-req {}, :client-id "client-1", :uid ::user
                :event event, :id (first event), :?data (second event)
                :?reply-fn nil})
             stop!
             (sente/start-server-chsk-router! ch
               (fn [{:keys [event]}]
                 (swap! processed_ conj event)
                 (case (first event)
                   ::fails (throw (ex-info "handler failed" {}))
                   ::three (deliver done true)
                   nil))
               {:error-handler
                (fn [error {:keys [event]}]
                  (swap! errors_ conj [(.getMessage ^Throwable error) event]))})]
         (try
           (doseq [event [[::one 1] [::fails 2] [::three 3]]]
             (async/>!! ch (event-msg event)))
           (is (= true (deref done *timeout-msecs* :timeout)))

           (is (= [[::one 1] [::fails 2] [::three 3]] @processed_))
           (is (= [["handler failed" [::fails 2]]] @errors_))

           (finally
             (stop!)
             (async/close! ch)))))))

#?(:clj
   (deftest test-reject-fn
     (testing "`:reject-fn` general rejection hook"
       (let [post-fn   (fn [opts] (:ajax-post-fn (sente/make-channel-socket-server! stub-sch-adapter opts)))
             reached?  (fn [resp] (= (:body resp) ::reached-adapter))
             skip-csrf (constantly :sente/skip-CSRF-check)] ; Disable built-in CSRF

         (testing "non-nil return rejects with that exact response"
           (let [resp ((post-fn {:reject-fn (fn [_] {:status 429 :body "slow down"})}) {})]
             (is (= 429         (:status resp)))
             (is (= "slow down" (:body   resp)))
             (is (not (reached? resp)))))

         (testing "runs BEFORE built-in checks (can reject what they'd allow)"
           ;; CSRF disabled => built-ins would pass, but reject-fn still rejects.
           (let [resp ((post-fn {:csrf-token-fn skip-csrf
                                 :reject-fn     (fn [_] {:status 403 :body "nope"})})
                       {})]
             (is (= "nope" (:body resp)))
             (is (not (reached? resp)))))

         (testing "nil return falls through to built-in checks (can't relax them)"
           ;; reject-fn abstains; default CSRF must still reject a token-less request.
           (is (= 403 (:status ((post-fn {:reject-fn (fn [_] nil)}) {})))))

         (testing "nil return + checks pass => request reaches adapter"
           (is (reached? ((post-fn {:csrf-token-fn skip-csrf
                                    :reject-fn     (fn [_] nil)})
                          {}))))

         (testing "receives the Ring request"
           (let [seen_ (atom nil)]
             ((post-fn {:csrf-token-fn skip-csrf
                        :reject-fn     (fn [req] (reset! seen_ req) nil)})
              {:foo :bar})
             (is (= {:foo :bar} @seen_))))))))

#?(:clj
   (deftest test-allowed-origins-rejection
     (testing "`:allowed-origins` rejects disallowed origins via bad-origin-fn"
       (let [post-fn  (fn [opts] (:ajax-post-fn (sente/make-channel-socket-server! stub-sch-adapter opts)))
             reached? (fn [resp] (= (:body resp) ::reached-adapter))
             ;; Disable CSRF so we isolate the origin check.
             base     {:csrf-token-fn   (constantly :sente/skip-CSRF-check)
                       :allowed-origins #{"http://site.com"}}]

         (testing "allowed origin passes"
           (is (reached? ((post-fn base) {:headers {"origin" "http://site.com"}}))))

         (testing "disallowed origin rejected (403)"
           (let [resp ((post-fn base) {:headers {"origin" "http://evil.com"}})]
             (is (= 403 (:status resp)))
             (is (not (reached? resp)))))))))

#?(:clj
   (deftest test-?unauthorized-fn-deprecated
     (testing "deprecated `:?unauthorized-fn` remains fully functional"
       (let [post-fn  (fn [opts] (:ajax-post-fn (sente/make-channel-socket-server! stub-sch-adapter opts)))
             reached? (fn [resp] (= (:body resp) ::reached-adapter))
             ;; Disable CSRF so we isolate the ?unauthorized-fn behaviour.
             base     {:csrf-token-fn (constantly :sente/skip-CSRF-check)}]

         (testing "non-nil return rejects"
           (is (= 401 (:status ((post-fn (assoc base :?unauthorized-fn (fn [_] {:status 401 :body "no"}))) {})))))

         (testing "nil return passes through"
           (is (reached? ((post-fn (assoc base :?unauthorized-fn (fn [_] nil))) {}))))))))

#?(:clj
   (deftest test-rejection-policy-precedence
     (testing "Request rejection uses the documented policy order"
       (let [response (fn [body] {:status 403, :body body})
             opts
             {:reject-fn       (fn [req] (when (:reject? req) (response :reject)))
              :csrf-token-fn   (constantly "token")
              :bad-csrf-fn     (fn [_] (response :csrf))
              :allowed-origins #{"https://allowed.example"}
              :bad-origin-fn   (fn [_] (response :origin))
              :authorized?-fn  :authorized?
              :unauthorized-fn (fn [_] (response :authorization))
              :?unauthorized-fn
              (fn [req] (when (:legacy-reject? req) (response :legacy)))}
             server (sente/make-channel-socket-server! stub-sch-adapter opts)
             base-req
             {:params {:client-id "client-1", :csrf-token "token"}
              :headers {"origin" "https://allowed.example"}
              :authorized? true}
             cases
             [[:reject
               (assoc base-req :reject? true :authorized? false
                 :legacy-reject? true
                 :params {:client-id "client-1"}
                 :headers {"origin" "https://evil.example"})]
              [:csrf
               (assoc base-req :authorized? false :legacy-reject? true
                 :params {:client-id "client-1"}
                 :headers {"origin" "https://evil.example"})]
              [:origin
               (assoc base-req :authorized? false :legacy-reject? true
                 :headers {"origin" "https://evil.example"})]
              [:authorization
               (assoc base-req :authorized? false :legacy-reject? true)]
              [:legacy (assoc base-req :legacy-reject? true)]
              [::reached-adapter base-req]]]

         (doseq [handler-key [:ajax-post-fn :ajax-get-or-ws-handshake-fn]
                 [expected req] cases]
           (is (= expected (:body ((handler-key server) req)))
             (str (name handler-key) " chooses " expected)))))))

;;;; Benching

(defn packed-len [x] #?(:clj (count x), :cljs (if (instance? js/Uint8Array x) (.-length x) (count x))))

(defn bench1
  "Returns Clj/s promise."
  [data packer]
  (when-let [laps *bench-laps*]
    #?(:clj
       (let [size (let [p (promise)] (i/pack packer nil data #(deliver p (packed-len (get % :value)))) (deref p *timeout-msecs* -1))
             t0   (enc/now-nano)]

         (dotimes [_ laps] (deref (roundtrip data packer) *timeout-msecs* :timeout))
         (deliver (promise)
           {:kib   (enc/round2 (/ size 1024))
            :msecs (enc/round2 (/ (- (enc/now-nano) t0) laps 1e6))}))

       :cljs
       (->
         (js/Promise. (fn [resolve _] (i/pack packer nil data #(resolve (get % :value)))))
         (.then
           (fn [packed]
             (let [size (packed-len packed)
                   t0   (enc/now-nano)
                   step
                   (fn step [i]
                     (if (< i laps)
                       (-> (roundtrip data packer) (.then (fn [_] (step (inc i)))))
                       (js/Promise.resolve
                         {:kib   (enc/round2 (/ size 1024))
                          :msecs (enc/round2 (/ (- (enc/now-nano) t0) laps 1e6))})))]

               (step 0))))))))

(def   benched_ (atom {}))
(defn >benched [id] (fn [val] (swap! benched_ assoc id val)))

(test/use-fixtures :once
  (taoensso.encore/test-fixtures
    {:before (fn [] (when *ci?* (println "In CI")))
     :after
     (fn []
       (println "Benchmarks:")
       (doseq [[id results] (sort @benched_)]
         (println (str "  " (name id) ": " results))))}))

(do
  (deftest bench-packer-edn        (test-promise (bench1 td1                 (ep/get-packer))                   (>benched :edn)))
  (deftest bench-packer-transit    (test-promise (bench1 td1                 (tp/get-packer))                   (>benched :transit)))
  (deftest bench-packer-msgpack    (test-promise (bench1 td1                 (mp/get-packer))                   (>benched :msgpack)))
  (deftest bench-packer-gz+edn     (test-promise (bench1 td1 (gz/wrap-packer (ep/get-packer) {:binary? false})) (>benched :edn+gz)))
  (deftest bench-packer-gz+transit (test-promise (bench1 td1 (gz/wrap-packer (tp/get-packer) {:binary? false})) (>benched :transit+gz)))
  (deftest bench-packer-gz+msgpack (test-promise (bench1 td1 (gz/wrap-packer (mp/get-packer) {:binary?  true})) (>benched :msgpack+gz))))

;;;;

#?(:cljs
   (defmethod test/report [:cljs.test/default :end-run-tests] [m]
     (when-not (test/successful? m)
       ;; Trigger non-zero `lein test-cljs` exit code for CI
       (throw (ex-info "ClojureScript tests failed" {})))))

#?(:cljs (test/run-tests))
