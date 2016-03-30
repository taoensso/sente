(ns taoensso.sente.client-tests
  "Tests to verify a client can connect to a server can interact using sente"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   [cljs.test :refer-macros [testing is are async]]
   [taoensso.encore :as enc]
   [devcards.core :refer-macros [deftest defcard]]
   [cljs.core.async :refer (<! >! put! chan)]
   [taoensso.sente :as sente]))

(goog-define
 ^{:doc "A Google Closure defined variable for overriding the window's hostname when connecting"}
 host-override false)

(def host
  "Takes on the value of host-override if it is set, otherwise just grabs the hostname from the window data"
  (if host-override
    host-override
    (:host (enc/get-window-location))))

(goog-define
  ^{:doc "A Google Closure defined variable for overriding the window's reported protocol when connecting"}
  protocol-override false)

(def protocol
  "Takes on the value of protocol-override if it is set, otherwise just grabs the hostname from the window data"
  (->
    (if protocol-override
      protocol-override
      (enc/get-window-location))
    :protocol
    (clojure.string/replace #":" "")))

(goog-define
  ^{:doc "A Google Closure defined variable for overriding automatic detection what type of socket sente will make"}
  channel-type-override false)

(def channel-type
  "Takes on the value of channel-type-override if it is set, otherwise defaults to :auto"
  (if channel-type-override
    (keyword channel-type-override)
    :auto))

(deftest
  check-protocol-is-valid
  (testing (str "protocol \"" protocol "\" is one of #{\"http\" \"https\"}" )
    (is (contains? #{"http" "https"} protocol))))

(deftest
  channel-type-is-valid
  (testing (str "channel-type \"" channel-type "\" is one of #{:auto :ajax :ws}" )
    (is (contains? #{:auto :ajax :ws} channel-type))))

(deftest
  basic-client-connect
  (testing "Client can make a connection with the chsk server"
    (is (let [{:keys [ch-recv]}
              (sente/make-channel-socket! "/chsk"
                                          {:type channel-type
                                           :host host})]
          (async done
                 (go
                   (let [{[ev-id ?ev-data] :event} (<! ch-recv)]
                     (is (= :chsk/state ev-id)
                         "First message should be a :chsk/state")
                     (is (= true (:open? ?ev-data))
                         "Connection should be open"))
                   (let [{[ev-id _] :event} (<! ch-recv)]
                     (is (= :chsk/handshake ev-id)
                         "Second message should be a :chsk/handshake"))
                   (done)))))))

(deftest
  basic-client-send
  (testing "Client can make a connection with the relay server and send a message with an acknowledgement"
    (is (let [{:keys [ch-recv send-fn state]}
              (sente/make-channel-socket! "/chsk"
                                          {:type channel-type
                                           :host host})
              payload (random-uuid)
              com (chan)]
          (async done
                 (go
                   (let [{[ev-id ?ev-data] :event} (<! ch-recv)]
                     (is (= :chsk/state ev-id)
                         "First message should be a :chsk/state")
                     (is (= true (:open? ?ev-data))
                         "Connection should be open"))
                   (let [{[ev-id [_ ?csrf-token]] :event} (<! ch-recv)]
                     (is (= :chsk/handshake ev-id)
                         "Second message should be a :chsk/handshake")
                     (is (= (:csrf-token @state) ?csrf-token)
                         "Second message should have a csrf-token that agrees with the state atom"))
                   (send-fn [:client/broadcast payload] 8000 #(go (>! com %)))
                   (let [[tag response-data] (<! com)]
                     (is (= :server/ack tag)
                         "Response tag from broadcast should be :server/ack")
                     (is (= payload response-data)
                         "Response data should be the UUID payload we sent"))
                   (done)))))))

(deftest
  basic-client-send-and-receive
  (testing "Client can make a connection with the relay server, send a message and listen for that message re-broadcast to itself"
    (is (let [{:keys [ch-recv send-fn]}
              (sente/make-channel-socket! "/chsk"
                                          {:type channel-type
                                           :host host})
              payload (random-uuid)
              com (chan)]
          (async done
                 (go
                   (is (= :chsk/state (-> ch-recv <! :event first))
                       "First message should be a :chsk/state")
                   (is (= :chsk/handshake (-> ch-recv <! :event first))
                       "Second message should be a :chsk/handshake")
                   (send-fn [:client/broadcast payload] 8000 #(go (>! com %)))
                   (is (= :server/ack (-> com <! first))
                       "Response tag from broadcast should be :server/ack")
                   (let [{[ev-id [tag ch-recv-data]] :event} (<! ch-recv)]
                     (is (= :chsk/recv ev-id)
                         "ev-id from channel message event should be :chsk/recv")
                     (is (= :server/broadcast tag)
                         "tag should be :server/broadcast")
                     (is (= payload ch-recv-data)
                         "Data received should be the UUID payload we broadcast"))
                   (done)))))))

(deftest
  client-send-and-receive-multi
  (testing "Client can make a two connections with the relay server and all both will receive a broadcast"
    (is (let [{ch-recv1 :ch-recv
               send-fn1 :send-fn} (sente/make-channel-socket!
                                   "/chsk"
                                   {:type channel-type
                                    :host host})
              {ch-recv2 :ch-recv} (sente/make-channel-socket!
                                   "/chsk"
                                   {:type channel-type
                                    :host host})
              payload (random-uuid)
              com (chan)]
          (async done
                 (go
                   (is (= :chsk/state (-> ch-recv1 <! :event first))
                       "First message should be a :chsk/state")
                   (is (= :chsk/handshake (-> ch-recv1 <! :event first))
                       "Second message should be a :chsk/handshake")
                   (let [{[ev-id2 ?ev-data2] :event} (<! ch-recv2)]
                     (is (= :chsk/state ev-id2)
                         "First message should be a :chsk/state (second client)")
                     (is (= true (:open? ?ev-data2)))
                     "Connection should report as open (second client)")
                   (is (= :chsk/handshake (-> ch-recv2 <! :event first))
                       "Second message should be a :chsk/handshake (second client)")
                   (send-fn1 [:client/broadcast payload] 8000 #(go (>! com %)))
                   (let [[tag response-data] (<! com)]
                     (is (= :server/ack tag)
                         "Response from server after sending payload should be an acknowledgement")
                     (is (= payload response-data)
                         "Response data from server should be the UUID payload we sent"))
                   (let [{[ev-id [tag ch-recv-data]] :event} (<! ch-recv1)]
                     (is (= :chsk/recv ev-id)
                         "Message should be :chsk/recv (first client)")
                     (is (= :server/broadcast tag)
                         "Tag for message should be a :server/broadcast (first client)")
                     (is (= payload ch-recv-data)
                         "Payload for message should be the UUID payload we sent (first client)"))
                   (let [{[ev-id2 [tag2 ch-recv-data2]] :event} (<! ch-recv2)]
                     (is (= :chsk/recv ev-id2)
                         "Message should be :chsk/recv (second client)")
                     (is (= :server/broadcast tag2)
                         "Tag for message should be a :server/broadcast (second client)")
                     (is (= payload ch-recv-data2)
                         "Payload for message should be the UUID payload we sent (second client)"))
                   (done)))))))

(deftest
  client-login
  (testing "Client can login with a particular uid"
    (is
     (let [{:keys [ch-recv state chsk]}
           (sente/make-channel-socket! "/chsk"
                                       {:type channel-type
                                        :host host})
           user-id (str (random-uuid))
           com (chan)]
       (async done
              (go
                (is (= :chsk/state (-> ch-recv <! :event first))
                    "First message should be a :chsk/state")
                (is (= :chsk/handshake (-> ch-recv <! :event first))
                    "Second message should be a :chsk/handshake")
                (sente/ajax-lite
                 (str protocol "://" host "/login")
                 {:method  :post
                  :headers {:X-CSRF-Token (:csrf-token @state)}
                  :params  {:user-id user-id}}
                 #(go (>! com %)))
                (let [ajax-response (<! com)]
                  (is (= 200 (:?status ajax-response))
                      "Ajax response code from login should be 200")
                  (is (= nil (:?error ajax-response))
                      "Ajax response from login should contain no errors"))
                (sente/chsk-reconnect! chsk)
                (let [{[ev-id3 ?ev-data3] :event} (<! ch-recv)]
                  (is (= :chsk/state ev-id3)
                      "After reconnect the first event should be a new state")
                  (is (= false (:open? ?ev-data3))
                      "After reconnect the first state should be disconnected"))
                (let [{[ev-id4 ?ev-data4] :event} (<! ch-recv)]
                  (is (= :chsk/state ev-id4)
                      "The second event after reconnect should be a new state")
                  (is (= true (:open? ?ev-data4))
                      "The second even after reconnect should report the connection is now open"))
                (is (= :chsk/handshake (-> ch-recv <! :event first))
                    "After reconnection there should be a handshake event")
                (is (= user-id (:uid @state))
                    "After the handshake event the new state should have the user id we logged in with")
                (done)))))))

(deftest
  multi-login
  (testing "Client can login with two different connections and send messages to one another"
    (is
     (let [{ch-recv1 :ch-recv
            state1 :state
            chsk1 :chsk
            send-fn1 :send-fn} (sente/make-channel-socket!
                                "/chsk"
                                {:type channel-type
                                 :host host})
           user-id1 (str (random-uuid))
           {ch-recv2 :ch-recv
            state2 :state
            chsk2 :chsk} (sente/make-channel-socket!
                          "/chsk"
                          {:type channel-type
                           :host host})
           user-id2 (str (random-uuid))
           payload (random-uuid)
           com (chan)]
       (async done
              (go
                (comment "Log in with first connection")
                (is (= :chsk/state (-> ch-recv1 <! :event first))
                    "First message to first client should be a :chsk/state")
                (is (= :chsk/handshake (-> ch-recv1 <! :event first))
                    "Second message to first client should be a :chsk/handshake")
                (sente/ajax-lite
                 "/login"
                 {:method  :post
                  :headers {:X-CSRF-Token (:csrf-token @state1)}
                  :params  {:user-id user-id1}}
                 #(go (>! com %)))
                (is (= 200 (-> com <! :?status))
                    "First client should successfully log in")
                (sente/chsk-reconnect! chsk1)
                (is (= false (-> ch-recv1 <! :event second :open?))
                    "After reconnect the first client should get an event that its connection is not open")
                (is (= true (-> ch-recv1 <! :event second :open?))
                    "After reconnect the first client should get another event that its connection *IS* open and reconnected")
                (is (= :chsk/handshake (-> ch-recv1 <! :event first))
                    "After reconnect the final message the first client gets should be a handshake")
                (is (= user-id1 (:uid @state1))
                    "The :uid state of the first connection should now report the ID we logged in with")

                (comment "Log in with second connection")
                (is (= :chsk/state (-> ch-recv2 <! :event first)))
                (is (= :chsk/handshake (-> ch-recv2 <! :event first)))
                (sente/ajax-lite
                 "/login"
                 {:method  :post
                  :headers {:X-CSRF-Token (:csrf-token @state2)}
                  :params  {:user-id user-id2}}
                 #(go (>! com %)))
                (is (= 200 (-> com <! :?status))
                    "Second client should successfully log in")
                (sente/chsk-reconnect! chsk2)
                (is (= false (-> ch-recv2 <! :event second :open?))
                    "After reconnect the second client should get an event that its connection is not open")
                (is (= true (-> ch-recv2 <! :event second :open?))
                    "After reconnect the second client should get another event that its connection *IS* open and reconnected")
                (is (= :chsk/handshake (-> ch-recv2 <! :event first))
                    "After reconnect the second message the first client gets should be a handshake")
                (is (= user-id2 (:uid @state2))
                    "The :uid state of the second connection should now report the ID we logged in with")

                (comment "have user-id1 send a uuid payload just for user-id2")
                (send-fn1 [:client/message {:recipient user-id2
                                            :message payload}] 8000 #(go (>! com %)))
                (is (= :server/ack (-> com <! first))
                    "Response tag from message transmission should be :server/ack")
                (let [{[ev-id [tag {:keys [message sender]}]] :event} (<! ch-recv2)]
                  (is (= :chsk/recv ev-id)
                      "ev-id for message to second connection should be :chsk/recv")
                  (is (= :server/relay tag)
                      "tag for message to second connection should be :server/relay")
                  (is (= user-id1 sender)
                      "sender of the message should be user-id1")
                  (is (= payload message)
                      "Payload should be the UUID they sent"))
                (done)))))))