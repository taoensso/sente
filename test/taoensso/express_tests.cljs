(ns taoensso.express-tests
  (:require
   [cljs.test :as test :refer [deftest testing is]]
   [taoensso.sente :as sente]
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
                  req  #js {:query #js {}, :body body}]
              ((:ajax-post-fn server)
               req #js {})
              (is (identical? req  (:body @ring-req_)))
              (is (identical? body (:taoensso.sente/ajax-body @ring-req_)))
              (is (empty? (:form-params @ring-req_))))))

        (is (= :sente/skip-CSRF-check
              ((:csrf-token-fn @server-opts_) nil)))

        (let [custom-csrf-token-fn (fn [_ring-req] "token")]
          (express/make-express-channel-socket-server!
            {:csrf-token-fn custom-csrf-token-fn})
          (is (identical? custom-csrf-token-fn
                (:csrf-token-fn @server-opts_))))))))

(defmethod test/report [:cljs.test/default :end-run-tests] [m]
  (when-not (test/successful? m)
    (throw (ex-info "ClojureScript Express tests failed" {}))))

(test/run-tests)
