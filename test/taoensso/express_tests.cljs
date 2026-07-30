(ns taoensso.express-tests
  (:require
   [cljs.test :as test :refer [deftest testing is]]
   [taoensso.sente :as sente]
   [taoensso.sente.interfaces :as i]
   [taoensso.sente.server-adapters.community.express :as express]
   [taoensso.sente.server-adapters.community.generic-node :as generic-node]))

(deftest express-adapter
  (testing "Express defaults and request conversion"
    (let [server-opts_ (atom nil)
          ring-req_    (atom nil)
          make-server
          (fn [_adapter opts]
            (reset! server-opts_ opts)
            {:ajax-get-or-ws-handshake-fn #(reset! ring-req_ %)
             :ajax-post-fn                #(reset! ring-req_ %)})]

      (with-redefs [sente/make-channel-socket-server! make-server
                    generic-node/get-sch-adapter          (fn [] ::adapter)]
        (let [server (express/make-express-channel-socket-server!)]
          (testing "raw request bodies are preserved for binary packers"
            (let [body (js/Uint8Array. #js [0 1 2])
                  req  #js {:query #js {}, :body body,
                            :headers #js {"origin" "http://site.com"}}]
              ((:ajax-post-fn server)
               req #js {})
              (is (identical? req  (:body @ring-req_)))
              (is (identical? body (:taoensso.sente/ajax-body @ring-req_)))
              (is (empty? (:form-params @ring-req_)))
              (is (= {"origin" "http://site.com"} (:headers @ring-req_))))))

        (is (= :sente/skip-CSRF-check
              ((:csrf-token-fn @server-opts_) nil)))

        (let [custom-csrf-token-fn (fn [_ring-req] "token")]
          (express/make-express-channel-socket-server!
            {:csrf-token-fn custom-csrf-token-fn})
          (is (identical? custom-csrf-token-fn
                (:csrf-token-fn @server-opts_))))))))

(deftest generic-node-adapter
  (testing "Generic Node channels preserve WebSocket and Ajax contracts"
    (let [handlers_ (atom {})
          sent_     (atom [])
          closed_   (atom 0)
          opened_   (atom nil)
          messages_ (atom [])
          closes_   (atom [])
          ws #js {}]
      (aset ws "OPEN" 1)
      (aset ws "readyState" 1)
      (aset ws "on"    (fn [event f] (swap! handlers_ assoc event f)))
      (aset ws "send"  (fn [msg ack] (swap! sent_ conj msg) (ack nil)))
      (aset ws "close"
        (fn []
          (aset ws "readyState" 3)
          (swap! closed_ inc)))

      (let [returned
            (i/ring-req->server-ch-resp (generic-node/get-sch-adapter)
              {:websocket ws}
              {:on-open  #(reset! opened_ [%1 %2])
               :on-msg   #(swap! messages_ conj [%1 %2 %3])
               :on-close #(swap! closes_   conj [%1 %2 %3])})
            [sch websocket?] @opened_]
        (is (identical? ws returned))
        (is websocket?)
        (is (i/sch-open? sch))
        (is (true? (i/sch-send! sch true "message")))
        (is (= ["message"] @sent_))
        ((get @handlers_ "message") "reply" nil)
        (is (= [[sch true "reply"]] @messages_))
        ((get @handlers_ "close") 1000 "done")
        (is (= [[sch true 1000]] @closes_))
        (is (true? (i/sch-close! sch)))
        (is (= 1 @closed_))
        (is (not (i/sch-open? sch)))))

    (let [handlers_ (atom {})
          ended_    (atom [])
          opened_   (atom nil)
          closes_   (atom [])
          response  #js {}
          payload   (js/Uint8Array. #js [0 1 2])]
      (aset response "on"  (fn [event f] (swap! handlers_ assoc event f)))
      (aset response "end" (fn [& [body]] (swap! ended_ conj body)))
      (aset response "write" (fn [_body cb] (cb)))

      (is (= {:keep-alive true}
            (i/ring-req->server-ch-resp (generic-node/get-sch-adapter)
              {:body #js {}, :response response}
              {:on-open  #(reset! opened_ [%1 %2])
               :on-close #(swap! closes_ conj [%1 %2 %3])})))
      (let [[sch websocket?] @opened_]
        (is (false? websocket?))
        (is (true? (i/sch-send! sch false payload)))
        (is (identical? payload (first @ended_)))
        (is (not (i/sch-open? sch)))
        ((get @handlers_ "finish"))
        (is (= [[sch false nil]] @closes_))))))
