<a href="https://www.taoensso.com" title="More stuff by @ptaoussanis at www.taoensso.com">
<img src="https://www.taoensso.com/taoensso-open-source.png" alt="Taoensso open-source" width="400"/></a>

**[CHANGELOG]** | [API] | current [Break Version]:

```clojure
[com.taoensso/sente "1.14.0-RC2"] ; NB Critical security update, see CHANGELOG for details
```

[![Dependencies Status](https://versions.deps.co/ptaoussanis/sente/status.svg)](https://versions.deps.co/ptaoussanis/sente)

> Please consider helping to [support my continued open-source Clojure/Script work]? 
> 
> Even small contributions can add up + make a big difference to help sustain my time writing, maintaining, and supporting Sente and other Clojure/Script libraries. **Thank you!**
>
> \- Peter Taoussanis

# Sente

### Realtime web comms for Clojure/Script

**Or**: We don't need no [Socket.IO]

**Or**: core.async + Ajax + WebSockets = _The Shiznizzle_

**Sente** is a small client+server library that makes it easy to build **reliable, high-performance realtime web applications with Clojure + ClojureScript**.

![Hero]

> **Sen-te** (先手) is a Japanese [Go] term used to describe a play with such an overwhelming follow-up that it demands an immediate response, leaving its player with the initiative.

(I'd also recommend checking out James Henderson's [Chord] and Kevin Lynagh's [jetty7-websockets-async] as possible alternatives!)

## Features
 * **Bidirectional a/sync comms** over both **WebSockets** and **Ajax** (auto-fallback)
 * **It just works**: auto keep-alives, buffering, protocol selection, reconnects
 * Efficient design incl. transparent event batching for **low-bandwidth use, even over Ajax**
 * Send **arbitrary Clojure vals** over [edn] or [Transit][] (JSON, MessagePack, etc.)
 * **Tiny API**: `make-channel-socket!` and you're good to go
 * Automatic, sensible support for users connected with **multiple clients** and/or devices simultaneously
 * Realtime info on **which users are connected** over which protocols (v0.10.0+)
 * **Flexible model**: use it anywhere you'd use WebSockets/Ajax/Socket.IO, etc.
 * Standard **Ring security model**: auth as you like, HTTPS when available, CSRF support, etc.
 * **Fully documented, with examples**
 * **Small codebase**: ~1.5k lines for the entire client+server implementation
 * **Supported servers**: [http-kit], [Immutant v2+], [nginx-clojure], node.js, [Aleph]

### Capabilities

Protocol   | client>server | client>server + ack/reply | server>user push
---------- | ------------- | ------------------------- | ----------------
WebSockets | ✓ (native)    | ✓ (emulated)              | ✓ (native)
Ajax       | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)

So you can ignore the underlying protocol and deal directly with Sente's unified API. It's simple, and exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional evented ack/reply model).

## Getting started

> Note that there's also a variety of full **[example projects]** available

Add the necessary dependency to your project:

```clojure
[com.taoensso/sente "1.14.0-RC2"]
```

### On the server (Clojure) side

First make sure that you're using one of the [supported web servers][] (PRs for additional server adapters welcome!).

Somewhere in your web app's code you'll already have a routing mechanism in place for handling Ring requests by request URL. If you're using [Compojure] for example, you'll have something that looks like this:

```clojure
(defroutes my-app
  (GET  "/"            req (my-landing-pg-handler  req))
  (POST "/submit-form" req (my-form-submit-handler req)))
```

For Sente, we're going to add 2 new URLs and setup their handlers:

```clojure
(ns my-server-side-routing-ns ; .clj
  (:require
    ;; <other stuff>
    [taoensso.sente :as sente] ; <--- Add this

    ;; Uncomment a web-server adapter --->
    ;; [taoensso.sente.server-adapters.http-kit      :refer (get-sch-adapter)]
    ;; [taoensso.sente.server-adapters.immutant      :refer (get-sch-adapter)]
    ;; [taoensso.sente.server-adapters.nginx-clojure :refer (get-sch-adapter)]
    ;; [taoensso.sente.server-adapters.aleph         :refer (get-sch-adapter)]
  ))

;;; Add this: --->
(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter) {})]

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
      ring.middleware.params/wrap-params))
```

> The `ring-ajax-post` and `ring-ajax-get-or-ws-handshake` fns will automatically handle Ring GET and POST requests to our channel socket URL (`"/chsk"`). Together these take care of the messy details of establishing + maintaining WebSocket or long-polling requests.

### On the client (ClojureScript) side

You'll setup something similar on the client side:

```clojure
(ns my-client-side-ns ; .cljs
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
   ;; <other stuff>
   [cljs.core.async :as async :refer (<! >! put! chan)]
   [taoensso.sente  :as sente :refer (cb-success?)] ; <--- Add this
  ))

;;; Add this: --->
(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same path as before
       {:type :auto ; e/o #{:auto :ajax :ws}
       })]
  (def chsk       chsk)
  (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
  (def chsk-send! send-fn) ; ChannelSocket's send API fn
  (def chsk-state state)   ; Watchable, read-only atom
  )
```

### Now what?

The client will automatically initiate a WebSocket or repeating long-polling connection to your server. Client<->server events are now ready to transmit over the `ch-chsk` channel.

**Last step**: you'll want to **hook your own event handlers up to this channel**. Please see one of the [example projects] for details.

#### Client-side API

 * `ch-recv` is a **core.async channel** that'll receive `event-msg`s
 * `chsk-send!` is a `(fn [event & [?timeout-ms ?cb-fn]])` for standard **client>server req>resp calls**

#### Server-side API

 * `ch-recv` is a **core.async channel** that'll receive `event-msg`s
 * `chsk-send!` is a `(fn [user-id event])` for async **server>user PUSH calls**

===============

Term          | Form
------------- | ----------------------------------------------------------------------
event         | `[<ev-id> <?ev-data>]`, e.g. `[:my-app/some-req {:data "data"}]`
server event-msg | `{:keys [event id ?data send-fn ?reply-fn uid ring-req client-id]}`
client event-msg | `{:keys [event id ?data send-fn]}`
`<ev-id>`     | A _namespaced_ keyword like `:my-app/some-req`
`<?ev-data>`  | An optional _arbitrary edn value_ like `{:data "data"}`
`:ring-req`   | Ring map for Ajax request or WebSocket's initial handshake request
`:?reply-fn`  | Present only when client requested a reply

#### Summary

 * So clients can use `chsk-send!` to send `event`s to the server and optionally request a reply with timeout
 * The server can likewise use `chsk-send!` to send `event`s to _all_ the clients (browser tabs, devices, etc.) of a particular connected user by his/her `user-id`
 * The server can also use an `event-msg`'s `?reply-fn` to _reply_ to a particular client `event` using an _arbitrary edn value_

> It's worth noting that the server>user push `(chsk-send! <user-id> <event>)` takes a mandatory **user-id** argument. See the FAQ later for more info.

### Ajax/Sente comparison: client>server

```clojure
(jayq/ajax ; Using the jayq wrapper around jQuery
 {:type :post :url "/some-url-on-server/"
  :data {:name "Rich Hickey"
         :type "Awesome"}
  :timeout 8000
  :success (fn [content text-status xhr]
             (do-something! content))
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

Some important differences to note:

 * The Ajax request is slow to initialize, and bulky (HTTP overhead)
 * The Sente request is pre-initialized (usu. WebSocket), and lean (edn/Transit protocol)

### Ajax/Sente comparison: server>user push

 * Ajax would require clumsy long-polling setup, and wouldn't easily support users connected with multiple clients simultaneously
 * Sente: `(chsk-send! "destination-user-id" [:some/alert-id <arb-clj-data-payload>])`

### Channel socket client state

Each time the channel socket client's state changes, a client-side `:chsk/state` event will fire that you can watch for and handle like any other event.

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

### Example projects

Link                            | Description
------------------------------- | --------------------------------------------------------
**[Official example]**          | **Official Sente reference example**, always up-to-date
[@laforge49/sente-boot]         | Example using Sente v1.11.0, Boot (also works with Windows)
[@laforge49/sente-boot-reagent] | Example using Sente v1.11.0, Boot, and Reagent
[@tiensonqin/lymchat]           | Example chat app using React Native
[@danielsz/system-websockets]   | Client-side UI, login and wiring of components
[@timothypratley/snakelake]     | Multiplayer snake game with screencast walkthrough
[@theasp/sente-nodejs-example]  | Ref. example adapted for Node.js servers ([Express], [Dog Fort]), as well as a node.js client
[@ebellani/carpet]              | Web+mobile interface for a remmitance application
[@danielsz/sente-system]        | Ref example adapted for [@danielsz/system]
[@danielsz/sente-boot]          | Ref example adapted for [boot]
[@seancorfield/om-sente]        | ??
[@tfoldi/data15-blackjack]      | Multiplayer blackjack game with documented source code
Your link here?                 | **PR's welcome!**

### FAQ

#### What is the `user-id` provided to the server>user push fn?

> There's now also a full `user-id`, `client-id` summary up [here](https://github.com/ptaoussanis/sente/issues/118#issuecomment-87378277)

For the server to push events, we need a destination. Traditionally we might push to a _client_ (e.g. browser tab). But with modern rich web applications and the increasing use of multiple simultaneous devices (tablets, mobiles, etc.) - the value of a _client_ push is diminishing. You'll often see applications (even by Google) struggling to deal with these cases.

Sente offers an out-the-box solution by pulling the concept of identity one level higher and dealing with unique _users_ rather than clients. **What constitutes a user is entirely at the discretion of each application**:

 * Each user-id may have zero _or more_ connected clients at any given time
 * Each user-id _may_ survive across clients (browser tabs, devices), and sessions

**To give a user an identity, either set the user's `:uid` Ring session key OR supply a `:user-id-fn` (takes request, returns an identity string) to the `make-channel-socket!` constructor.**

If you want a simple _per-session_ identity, generate a _random uuid_. If you want an identity that persists across sessions, try use something with _semantic meaning_ that you may already have like a database-generated user-id, a login email address, a secure URL fragment, etc.

> Note that user-ids are used **only** for server>user push. client>server requests don't take a user-id.

As of Sente v0.13.0+ it's also possible to send events to `:sente/all-users-without-uid`.

#### How do I integrate Sente with my usual login/auth procedure?

This is trivially easy as of Sente v0.13.0+. Please see one of the [example projects][] for details.

#### Will Sente work with Reactjs/Reagent/Om/Pedestel/etc.?

Sure! Sente's just a client<->server comms mechanism so it'll work with any view/rendering approach you'd like.

I have a strong preference for [Reagent] myself, so would recommend checking that out first if you're still evaluating options.

#### What if I need to use JSON, XML, raw strings, etc.?

As of v1, Sente uses an extensible client<->server serialization mechanism. It uses edn by default since this usu. gives good performance and doesn't require any external dependencies. The [reference example project] shows how you can plug in an alternative de/serializer. In particular, note that Sente ships with a Transit de/serializer that allows manual or smart (automatic) per-payload format selection.

#### How do I add custom Transit read and write handlers?

To add custom handlers to the TransitPacker, pass them in as `writer-opts` and `reader-opts` when creating a `TransitPacker`. These arguments are the same as the `opts` map you would pass directly to `transit/writer`. The code sample below shows how you would do this to add a write handler to convert [Joda-Time] `DateTime` objects to Transit `time` objects.

```clj
(ns my-ns.app
  (:require [cognitect.transit :as transit]
            [taoensso.sente.packers.transit :as sente-transit])
  (:import [org.joda.time DateTime ReadableInstant]))

;; From http://increasinglyfunctional.com/2014/09/02/custom-transit-writers-clojure-joda-time/
(def joda-time-writer
  (transit/write-handler
    (constantly "m")
    (fn [v] (-> ^ReadableInstant v .getMillis))
    (fn [v] (-> ^ReadableInstant v .getMillis .toString))))

(def packer (sente-transit/->TransitPacker :json {:handlers {DateTime joda-time-writer}} {}))
```

#### How do I route client/server events?

However you like! If you don't have many events, a simple `cond` will probably do. Otherwise a multimethod dispatching against event ids works well (this is the approach taken in the [reference example project]).

#### Security: is there HTTPS support?

Yup, it's automatic for both Ajax and WebSockets. If the page serving your JavaScript (ClojureScript) is running HTTPS, your Sente channel sockets will run over HTTPS and/or the WebSocket equivalent (WSS).

#### Security: CSRF protection?

**This is important**. Sente has support, but you'll need to use middleware like `ring-anti-forgery` to generate and check CSRF codes. The `ring-ajax-post` handler should be covered (i.e. protected).

Please see one of the [example projects] for a fully-baked example.

#### Pageload: How do I know when Sente is ready client-side?

You'll want to listen on the receive channel for a `[:chsk/state [_ {:first-open? true}]]` event. That's the signal that the socket's been established.

#### How can server-side channel socket events modify a user's session?

> **Update**: [@danielsz] has kindly provided a detailed example [here](https://github.com/ptaoussanis/sente/issues/62#issuecomment-58790741).

Recall that server-side `event-msg`s are of the form `{:ring-req _ :event _ :?reply-fn _}`, so each server-side event is accompanied by the relevant[*] Ring request.

> For WebSocket events this is the initial Ring HTTP handshake request, for Ajax events it's just the Ring HTTP Ajax request.

The Ring request's `:session` key is an immutable value, so how do you modify a session in response to an event? You won't be doing this often, but it can be handy (e.g. for login/logout forms).

You've got two choices:

1. Write any changes directly to your Ring SessionStore (i.e. the mutable state that's actually backing your sessions). You'll need the relevant user's session key, which you can find under your Ring request's `:cookies` key. This is flexible, but requires that you know how+where your session data is being stored.

2. Just use regular HTTP Ajax requests for stuff that needs to modify sessions (like login/logout), since these will automatically go through the usual Ring session middleware and let you modify a session with a simple `{:status 200 :session <new-session>}` response. This is the strategy the reference example takes.

#### Lifecycle management (component management/shutdown, etc.)

Using something like [@stuartsierra/component] or [@palletops/leaven]?

Most of Sente's state is held internally to each channel socket (the map returned from client/server calls to `make-channel-socket!`). The absence of global state makes things like testing, and running multiple concurrent connections easy. It also makes integration with your component management easy.

The only thing you _may_[1] want to do on component shutdown is stop any router loops that you've created to dispatch events to handlers. The client/server side `start-chsk-router!` fns both return a `(fn stop [])` that you can call to do this.

> [1] The cost of _not_ doing this is actually negligible (a single parked go thread).

There's also a couple lifecycle libraries that include Sente components:

 1. [@danielsz/system] for use with [@stuartsierra/component]
 2. [@palletops/bakery] for use with [@palletops/leaven]

#### How to debug/benchmark Sente at the protocol level?

[@arichiardi] has kindly provided notes on some of Sente's current implementation details [here](https://github.com/ptaoussanis/sente/wiki/Debugging-and-benchmarking-at-the-protocol-level).

#### Any other questions?

If I've missed something here, feel free to open a GitHub issue or pop me an email!

## Contacting me / contributions

Please use the project's [GitHub issues page] for all questions, ideas, etc. **Pull requests welcome**. See the project's [GitHub contributors page] for a list of contributors.

Otherwise, you can reach me at [Taoensso.com]. Happy hacking!

\- [Peter Taoussanis]

## License

Distributed under the [EPL v1.0] \(same as Clojure).  
Copyright &copy; 2014-2016 [Peter Taoussanis].

<!--- Standard links -->
[Taoensso.com]: https://www.taoensso.com
[Peter Taoussanis]: https://www.taoensso.com
[@ptaoussanis]: https://www.taoensso.com
[More by @ptaoussanis]: https://www.taoensso.com
[Break Version]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md
[support my continued open-source Clojure/Script work]: http://taoensso.com/clojure/backers

<!--- Standard links (repo specific) -->
[CHANGELOG]: https://github.com/ptaoussanis/sente/releases
[API]: http://ptaoussanis.github.io/sente/
[GitHub issues page]: https://github.com/ptaoussanis/sente/issues
[GitHub contributors page]: https://github.com/ptaoussanis/sente/graphs/contributors
[EPL v1.0]: https://raw.githubusercontent.com/ptaoussanis/sente/master/LICENSE
[Hero]: https://raw.githubusercontent.com/ptaoussanis/sente/master/hero.jpg "Source: http://almostsente.tumblr.com/"

<!--- Unique links -->
[Socket.IO]: http://socket.io/
[Chord]: https://github.com/jarohen/chord
[jetty7-websockets-async]: https://github.com/lynaghk/jetty7-websockets-async
[Go]: https://en.wikipedia.org/wiki/Go_game
[edn]: https://github.com/edn-format/edn
[Transit]: https://github.com/cognitect/transit-cljs
[http-kit]: https://github.com/http-kit/http-kit
[Immutant v2+]: http://immutant.org/
[nginx-clojure]: https://github.com/nginx-clojure/nginx-clojure
[Aleph]: https://github.com/ztellman/aleph
[example projects]: #example-projects

[supported web servers]: https://github.com/ptaoussanis/sente/issues/102

[Compojure]: https://github.com/weavejester/compojure
[Official example]: https://github.com/ptaoussanis/sente/tree/master/example-project
[reference example project]: https://github.com/ptaoussanis/sente/tree/master/example-project
[Cider]: https://github.com/clojure-emacs/cider

[@tiensonqin/lymchat]: https://github.com/tiensonqin/lymchat
[@danielsz/sente-boot]: https://github.com/danielsz/sente-boot
[@danielsz/sente-system]: https://github.com/danielsz/sente-system
[@danielsz/system-websockets]: https://github.com/danielsz/system-websockets
[@seancorfield/om-sente]: https://github.com/seancorfield/om-sente
[@ebellani/carpet]: https://github.com/ebellani/carpet
[@theasp/sente-nodejs-example]: https://github.com/theasp/sente-nodejs-example
[@timothypratley/snakelake]: https://github.com/timothypratley/snakelake
[@tfoldi/data15-blackjack]: https://github.com/tfoldi/data15-blackjack
[Express]: http://expressjs.com/
[Dog Fort]: https://github.com/whamtet/dogfort

[@laforge49/sente-boot]: https://github.com/laforge49/sente-boot/
[@laforge49/sente-boot-reagent]: https://github.com/laforge49/sente-boot-reagent

[boot]: http://boot-clj.com/
[@danielsz/system]: https://github.com/danielsz/system

[Reagent]: http://reagent-project.github.io/
[Joda-Time]: http://www.joda.org/joda-time/
[@danielsz]: https://github.com/danielsz
[@arichiardi]: https://github.com/arichiardi

[@stuartsierra/component]: https://github.com/stuartsierra/component
[@palletops/leaven]: https://github.com/palletops/leaven
[@palletops/bakery]: https://github.com/palletops/bakery
