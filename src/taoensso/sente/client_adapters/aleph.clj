(ns taoensso.sente.client-adapters.aleph
  "Sente client adapter for Aleph (https://github.com/ztellman/aleph)."
  {:author "Frozenlock"}
  (:require
   [taoensso.sente.interfaces :as i]  
   [aleph.http        :as aleph]
   [manifold.stream   :as s]
   [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]))


(extend-type manifold.stream.core.IEventSink
  i/IClientWebSocket
  (cws-close! [cws]       
    (s/close! cws))
  (cws-send!  [cws msg]
    (if (s/closed? cws)
      false
      (do (s/put! cws msg)
          true))))

(defn aleph-create-client-websocket! 
  "Create a websocket with provided URL. Return an IClientWebSocket
  implementation, or nil if connection failed."
  [url {:keys [on-msg on-error on-close]}]
  (let [ws (try @(aleph/websocket-client url)
                (catch Exception e 
                  (do (errorf e)
                      nil)))]
    (when ws
      (when on-msg (s/consume (fn [msg] 
                                (try (on-msg msg)
                                     (catch Exception e
                                       (when on-error e)))) ws))
      (when on-close (s/on-closed ws (fn [] (on-close [:aleph/closed]))))
      ws)))
