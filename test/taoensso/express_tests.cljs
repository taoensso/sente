(ns taoensso.express-tests
  (:require
   [cljs.test :as test :refer [deftest testing is]]
   [taoensso.sente :as sente]
   [taoensso.sente.server-adapters.community.express :as express]
   [taoensso.sente.server-adapters.community.generic-node :as generic-node]))

(deftest csrf-policy
  (testing "The Express adapter delegates CSRF validation to upstream middleware"
    (let [server-opts_ (atom nil)
          make-server
          (fn [_adapter opts]
            (reset! server-opts_ opts)
            {:ajax-get-or-ws-handshake-fn (fn [_ring-req])
             :ajax-post-fn                (fn [_ring-req])})]

      (with-redefs [sente/make-channel-socket-server! make-server
                    generic-node/get-sch-adapter          (fn [] ::adapter)]
        (express/make-express-channel-socket-server!)
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
