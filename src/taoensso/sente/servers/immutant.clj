(ns taoensso.sente.servers.immutant
  "Experimental (pre-alpha): subject to change.
  Optional Immutant[1] Channel implementation for use with Sente.
  [1] http://immutant.org/."
  (:require [taoensso.sente.interfaces :as i]))

(i/when-import org.projectodd.wunderboss.web.async.Channel
  (require '[immutant.web.async :as async])

  (extend-type Channel
    i/IAsyncNetworkChannel
    (open? [ch] (async/open? ch))
    (close [ch] (async/close ch))
    (send!
      ([ch msg]        (i/send! ch msg false))
      ([ch msg close?] (async/send! ch msg :close? close?))))

  (i/provide-as-channel!
    (fn [request & {:as callbacks :keys [on-receive]}]
      (async/as-channel
        request
        (-> callbacks
          (assoc :on-message on-receive)
          (dissoc :on-receive))))))
