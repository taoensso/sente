(ns taoensso.sente-tests
  "Due to the complexity of automated browser tests, Sente has
  traditionally been tested manually. Before each release, a suite
  of checks have been done against the reference example project.

  In the hope of eventually doing more of this work automatically,
  the current namespace provides a groundwork for future automated
  tests.

  PRs very welcome if you'd like to contribute to this effort!"

  (:require
   [clojure.test                     :as test :refer [deftest testing is]]
   ;; [clojure.test.check            :as tc]
   ;; [clojure.test.check.generators :as tc-gens]
   ;; [clojure.test.check.properties :as tc-props]
   [clojure.string  :as str]
   [taoensso.encore :as enc]

   ;; :cljs cannot compile taoensso.sente under :nodejs target
   #?(:clj [taoensso.sente :as sente])

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
