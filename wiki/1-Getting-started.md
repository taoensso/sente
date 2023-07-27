> See also [here](./3-Example-projects) for **full example projects** 👈

# Setup

## Dependency

Add the [relevant dependency](../#latest-releases) to your project:

```clojure
Leiningen: [com.taoensso/sente               "x-y-z"] ; or
deps.edn:   com.taoensso/sente {:mvn/version "x-y-z"}
```

## Server-side setup

First make sure that you're using one of the [supported web servers](https://github.com/ptaoussanis/sente/tree/master/src/taoensso/sente/server_adapters) (PRs for additional server adapters welcome!).

Somewhere in your web app's code you'll already have a routing mechanism in place for handling Ring requests by request URL. If you're using [Compojure](https://github.com/weavejester/compojure) for example, you'll have something that looks like this:

```clojure
(defroutes my-app
  (GET  "/"            req (my-landing-pg-handler  req))
  (POST "/submit-form" req (my-form-submit-handler req)))
```

For Sente, we're going to add 2 new URLs and setup their handlers:

```clojure
(ns my-server-side-routing-ns ; Usually a .clj file
  (:require
    ;; <other stuff>
    [taoensso.sente :as sente] ; <--- Add this

    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]] ; <--- Recommended

    ;; Uncomment a web-server adapter --->
    ;; [taoensso.sente.server-adapters.http-kit      :refer [get-sch-adapter]]
    ;; [taoensso.sente.server-adapters.immutant      :refer [get-sch-adapter]]
    ;; [taoensso.sente.server-adapters.nginx-clojure :refer [get-sch-adapter]]
    ;; [taoensso.sente.server-adapters.aleph         :refer [get-sch-adapter]]
  ))

;;; Add this: --->
(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server! (get-sch-adapter) {})]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defroutes my-app-routes
  ;; <other stuff>

  ;;; Add these 2 entries: --->
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  )

(def my-app
  (-> my-app-routes
      ;; Add necessary Ring middleware:
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params
      ring.middleware.anti-forgery/wrap-anti-forgery
      ring.middleware.session/wrap-session))
```

> The `ring-ajax-post` and `ring-ajax-get-or-ws-handshake` fns will automatically handle Ring GET and POST requests to our channel socket URL (`"/chsk"`). Together these take care of the messy details of establishing + maintaining WebSocket or long-polling requests.

Add a CSRF token somewhere in your HTML:

```
(let [csrf-token (force ring.middleware.anti-forgery/*anti-forgery-token*)]
  [:div#sente-csrf-token {:data-csrf-token csrf-token}])
```

## Client-side setup

You'll setup something similar on the client side:

```clojure
(ns my-client-side-ns ; Usually a .cljs file
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
   ;; <other stuff>
   [cljs.core.async :as async :refer (<! >! put! chan)]
   [taoensso.sente  :as sente :refer (cb-success?)] ; <--- Add this
  ))

;;; Add this: --->

(def ?csrf-token
  (when-let [el (.getElementById js/document "sente-csrf-token")]
    (.getAttribute el "data-csrf-token")))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
       "/chsk" ; Note the same path as before
       ?csrf-token
       {:type :auto ; e/o #{:auto :ajax :ws}
       })]

  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )
```

# Usage

After setup, the client will automatically initiate a WebSocket or repeating long-polling connection to your server. Client<->server events are now ready to transmit over the `ch-chsk` channel.

**Last step**: you'll want to **hook your own event handlers up to this channel**. Please see one of the [example projects](./3-Example-projects) and/or [API docs](http://ptaoussanis.github.io/sente/) for details.

## Client-side API

 * `ch-recv` is a **core.async channel** that'll receive `event-msg`s
 * `chsk-send!` is a `(fn [event & [?timeout-ms ?cb-fn]])` for standard **client>server req>resp calls**

Let's compare some Ajax and Sente code for sending an event from the client to the server:

```clojure
(jayq/ajax ; Using the jayq wrapper around jQuery
 {:type :post :url "/some-url-on-server/"
  :data {:name "Rich Hickey"
         :type "Awesome"}
  :timeout 8000
  :success (fn [content text-status xhr] (do-something! content))
  :error   (fn [xhr text-status] (error-handler!))})

(chsk-send! ; Using Sente
  [:some/request-id {:name "Rich Hickey" :type "Awesome"}] ; Event
  8000 ; Timeout
  ;; Optional callback:
  (fn [reply] ; Reply is arbitrary Clojure data
    (if (sente/cb-success? reply) ; Checks for :chsk/closed, :chsk/timeout, :chsk/error
      (do-something! reply)
      (error-handler!))))
```

Note:

 * The Ajax request is slow to initialize, and bulky (HTTP overhead)
 * The Sente request is pre-initialized (usu. WebSocket), and lean (edn/Transit protocol)

## Server-side API

 * `ch-recv` is a **core.async channel** that'll receive `event-msg`s
 * `chsk-send!` is a `(fn [user-id event])` for async **server>user PUSH calls**

For asynchronously pushing an event from the server to the client:

 * Ajax would require a clumsy long-polling setup, and wouldn't easily support users connected with multiple clients simultaneously
 * Sente: `(chsk-send! "destination-user-id" [:some/alert-id <arb-clj-data-payload>])`

**Important**: note that Sente intentionally offers server to [user](./2-Client-and-user-ids) push rather than server>client push. A single user may have >=0 associated clients.

## Types and terminology

Term             | Form
---------------- | ----------------------------------------------------------------------
event            | `[<ev-id> <?ev-data>]`, e.g. `[:my-app/some-req {:data "data"}]`
server event-msg | `{:keys [event id ?data send-fn ?reply-fn uid ring-req client-id]}`
client event-msg | `{:keys [event id ?data send-fn]}`
`<ev-id>`        | A _namespaced_ keyword like `:my-app/some-req`
`<?ev-data>`     | An optional _arbitrary edn value_ like `{:data "data"}`
`:ring-req`      | Ring map for Ajax request or WebSocket's initial handshake request
`:?reply-fn`     | Present only when client requested a reply


## Summary

 * Clients use `chsk-send!` to send `event`s to the server and optionally request a reply with timeout
 * Server uses `chsk-send!` to send `event`s to _all_ the clients (browser tabs, devices, etc.) of a particular connected user by his/her [user-id](./2-Client-and-user-ids).
 * The server can also use an `event-msg`'s `?reply-fn` to _reply_ to a particular client `event` using an _arbitrary edn value_

## Channel socket state

Each time the client's channel socket state changes, a client-side `:chsk/state` event will fire that you can watch for and handle like any other event.

The event form is `[:chsk/state [<old-state-map> <new-state-map>]]` with the following possible state map keys:

Key             | Value
--------------- | --------------------------------------------------------
:type           | e/o `#{:auto :ws :ajax}`
:open?          | Truthy iff chsk appears to be open (connected) now
:ever-opened?   | Truthy iff chsk handshake has ever completed successfully
:first-open?    | Truthy iff chsk just completed first successful handshake
:uid            | User id provided by server on handshake,    or nil
:csrf-token     | CSRF token provided by server on handshake, or nil
:handshake-data | Arb user data provided by server on handshake
:last-ws-error  | `?{:udt _ :ev <WebSocket-on-error-event>}`
:last-ws-close  | `?{:udt _ :ev <WebSocket-on-close-event> :clean? _ :code _ :reason _}`
:last-close     | `?{:udt _ :reason _}`, with reason e/o `#{nil :requested-disconnect :requested-reconnect :downgrading-ws-to-ajax :unexpected}`