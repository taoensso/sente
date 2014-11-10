**[API docs][]** | **[CHANGELOG][]** | [other Clojure libs][] | [Twitter][] | [contact/contrib](#contact--contributing) | current [Break Version][]:

```clojure
[com.taoensso/sente "1.2.0"] ; Please see CHANGELOG for details
```

# Sente, channel sockets for Clojure

![Almost sente](https://github.com/ptaoussanis/sente/raw/master/almost-sente.jpg)

> **Sen-te** (先手) is a Japanese [Go][] term used to describe a play with such an overwhelming follow-up that it elicits an immediate response, thus leaving its player with the initiative.

**Sente** is a small client+server library that makes it easy to build **reliable, high-performance realtime web applications with Clojure**.

Or: **We don't need no [Socket.IO][]**
Or: **The missing piece in Clojure's web application story**
Or: **Clojure(Script) + core.async + WebSockets/Ajax = _The Shiz_**

(I'd also recommend checking out James Henderson's [Chord][] and Kevin Lynagh's [jetty7-websockets-async][] as possible alternatives!)

## What's in the box™?
  * **Bidirectional a/sync comms** over both **WebSockets** and **Ajax** (auto-fallback).
  * **Robust**: auto keep-alives, buffering, protocol selection, reconnects. **It just works™**.
  * Highly efficient design incl. transparent event batching for **low-bandwidth use, even over Ajax**.
  * Send arbitrary Clojure vals over [edn][] or [Transit](https://github.com/cognitect/transit-cljs) (JSON, MessagePack, etc.).
  * **Tiny, simple API**: `make-channel-socket!` and you're good to go.
  * Automatic, sensible support for users connected with **multiple clients** and/or devices simultaneously.
  * Realtime info on **which users are connected** over which protocols (v0.10.0+).
  * **Flexible model**: use it anywhere you'd use WebSockets/Ajax/Socket.IO, etc.
  * Standard **Ring security model**: auth as you like, HTTPS when available, CSRF support, etc.
  * **Fully documented, with examples**.
  * **Small codebase**: ~1k lines for the entire client+server implementation.
  * **Supported servers**: currently only [http-kit][] but a pluggable interface is planned.


### Capabilities

Protocol            | client>server | client>server + ack/reply | server>user push |
------------------- | ------------- | ------------------------- | ---------------- |
WebSockets          | ✓ (native)    | ✓ (emulated)              | ✓ (native)       |
Ajax                | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)     |

So you can ignore the underlying protocol and deal directly with Sente's unified API. It's simple, and exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional evented ack/reply model).


## Getting started

> Note that there's also a full **[reference example project][]** in this repo.

Add the necessary dependency to your [Leiningen][] `project.clj`. This'll provide your project with both the client (ClojureScript) + server (Clojure) side library code:

```clojure
[com.taoensso/sente "1.2.0"]
```

### On the server (Clojure) side

First, make sure you're using [http-kit][] as your Clojure web server. If you're using the standard Ring server (Jetty), http-kit is [almost](http://http-kit.org/migration.html) a drop-in replacement.

> **Why http-kit**? Besides being a great web server, it currently offers by far the best high-concurrency support which is something Sente needs to lean on for WebSocket and long-polling connections.

Somewhere in your web app's code you'll already have a routing mechanism in place for handling Ring requests by request URL. If you're using [Compojure](https://github.com/weavejester/compojure) for example, you'll have something that looks like this:

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
   ))

;;; Add this: --->
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

(defroutes my-app
  ;; <other stuff>

  ;;; Add these 2 entries: --->
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  )
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

**Last step**: you'll want to **hook your own event handlers up to this channel**. Please see the [reference example project] for one way of doing this.

#### Client-side API

  * `ch-recv` is a **core.async channel** that'll receive `event-msg`s.
  * `chsk-send!` is a `(fn [event & [?timeout-ms ?cb-fn]])`. This is for standard **client>server req>resp calls**.

#### Server-side API

  * `ch-recv` is a **core.async channel** that'll receive `event-msg`s.
  * `chsk-send!` is a `(fn [user-id event])`. This is for async **server>user PUSH calls**.

===============

Term          | Form                                                                  |
------------- | --------------------------------------------------------------------- |
**event**     | `[<ev-id> <?ev-data>]`, e.g. `[:my-app/some-req {:data "data"}]`      |
**event-msg** (server) | `{:event _ :send-fn _ :?reply-fn _ :ring-req _ <...>}`       |
**event-msg** (client) | `{:event _ :send-fn _ <...>}`                                |
`<ev-id>`     | A _namespaced_ keyword like `:my-app/some-req`                        |
`<?ev-data>`  | An optional _arbitrary edn value_ like `{:data "data"}`               |
`:ring-req`   | Ring map for Ajax request or WebSocket's initial handshake request    |
`:?reply-fn`  | Present only when client requested a reply.                           |

#### Summary

  * So clients can use `chsk-send!` to send `event`s to the server. They can optionally request a reply, with timeout.
  * The server can likewise use `chsk-send!` to send `event`s to _all_ the clients (browser tabs, devices, etc.) of a particular connected user by his/her `user-id`.
  * The server can also use an `event-msg`'s `?reply-fn` to _reply_ to a particular client `event` using an _arbitrary edn value_.

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
  [:some/request-id {:name "Rich Hickey" :type "Awesome"}] ; event
  8000 ; timeout
  ;; Optional callback:
  (fn [edn-reply]
    (if (sente/cb-success? edn-reply) ; Checks for :chsk/closed, :chsk/timeout, :chsk/error
      (do-something! edn-reply)
      (error-handler!))))
```

Some important differences to note:

  * The Ajax request is slow to initialize, and bulky (HTTP overhead).
  * The Sente request is pre-initialized (usu. WebSocket), and lean (edn/Transit protocol).

### Ajax/Sente comparison: server>user push

  * Ajax would require clumsy long-polling setup, and wouldn't easily support users connected with multiple clients simultaneously.
  * Sente: `(chsk-send! "destination-user-id" [:some/alert-id <edn-payload>])`.


### FAQ

#### What is the `user-id` provided to the server>user push fn?

For the server to push events, we need a destination. Traditionally we might push to a _client_ (e.g. browser tab). But with modern rich web applications and the increasing use of multiple simultaneous devices (tablets, mobiles, etc.) - the value of a _client_ push is diminishing. You'll often see applications (even by Google) struggling to deal with these cases.

Sente offers an out-the-box solution by pulling the concept of identity one level higher and dealing with unique _users_ rather than clients. **What constitutes a user is entirely at the discretion of each application**:

  * Each user-id may have zero _or more_ connected clients at any given time.
  * Each user-id _may_ survive across clients (browser tabs, devices), and sessions.

**To give a user an identity, either set the user's `:uid` Ring session key OR supply a `:user-id-fn` (takes request, returns an identity string) to the `make-channel-socket!` constructor.**

If you want a simple _per-session_ identity, generate a _random uuid_. If you want an identity that persists across sessions, try use something with _semantic meaning_ that you may already have like a database-generated user-id, a login email address, a secure URL fragment, etc.

> Note that user-ids are used **only** for server>user push. client>server requests don't take a user-id.

As of Sente v0.13.0+ it's also possible to send events to `:sente/all-users-without-uid`.

#### How do I integrate Sente with my usual login/auth procedure?

This is trivially easy as of Sente v0.13.0+. Please see the [reference example project][] which now includes a basic login form.

#### Will Sente work with [React][]/[Reagent][]/[Om][]/etc.?

Sure! I use it with Reagent myself. Sente's just a client<->server comms mechanism.

#### What if I need to use JSON, XML, raw strings, etc.?

As of v1, Sente uses an extensible client<->server serialization mechanism. It uses edn by default since this usu. gives good performance and doesn't require any external dependencies. The [reference example project][] shows how you can plug in an alternative de/serializer. In particular, note that Sente ships with a Transit de/serializer that allows manual or smart (automatic) per-payload format selection.

#### How do I route client/server events?

However you like! If you don't have many events, a simple `cond` will probably do. Otherwise a multimethod dispatching against event ids works well (this is the approach taken in the [reference example project][].

#### Security: is there HTTPS support?

Yup, it's automatic for both Ajax and WebSockets. If the page serving your JavaScript (ClojureScript) is running HTTPS, your Sente channel sockets will run over HTTPS and/or the WebSocket equivalent (WSS).

#### Security: CSRF protection?

**This is important**. Sente has support, but you'll need to use middleware like `ring-anti-forgery` to generate and check CSRF codes. The `ring-ajax-post` handler should be covered (i.e. protected).

The [reference example project][] has a fully-baked example.

#### Pageload: How do I know when Sente is ready client-side?

You'll want to listen on the receive channel for a `[:chsk/state {:first-open? true}]` event. That's the signal that the socket's been established.

#### Examples: wherefore art thou?

There's a full [reference example project][] in the repo. Call `lein start-dev` in that dir to get a (headless) development repl that you can connect to with [Cider][] (emacs) or your IDE.

Further instructions are provided in the relevant namespace.

#### How can server-side channel socket events modify a user's session?

> **Update**: [@danielsz](https://github.com/danielsz) has kindly provided a detailed example [here](https://github.com/ptaoussanis/sente/issues/62#issuecomment-58790741).

Recall that server-side `event-msg`s are of the form `{:ring-req _ :event _ :?reply-fn _}`, so each server-side event is accompanied by the relevant[*] Ring request.

> For WebSocket events this is the initial Ring HTTP handshake request, for Ajax events it's just the Ring HTTP Ajax request.

The Ring request's `:session` key is an immutable value, so how do you modify a session in response to an event? You won't be doing this often, but it can be handy (e.g. for login/logout forms).

You've got two choices:

1. Write any changes directly to your Ring SessionStore (i.e. the mutable state that's actually backing your sessions). You'll need the relevant user's session key, which you can find under your Ring request's `:cookies` key. This is flexible, but requires that you know how+where your session data is being stored.

2. Just use regular HTTP Ajax requests for stuff that needs to modify sessions (like login/logout), since these will automatically go through the usual Ring session middleware and let you modify a session with a simple `{:status 200 :session <new-session>}` response. This is the strategy the reference example takes.

#### Lifecycle management (component management/shutdown, etc.)

Using something like [stuartsierra/component] or [palletops/leaven]?

Most of Sente's state is held internally to each channel socket (the map returned from client/server calls to `make-channel-socket!`). The absence of global state makes things like testing, and running multiple concurrent connections easy. It also makes integration with your component management easy.

The only thing you _may_[1] want to do on component shutdown is stop any router loops that you've created to dispatch events to handlers. The client/server side `start-chsk-router!` fns both return a `(fn stop [])` that you can call to do this.

> [1] The cost of _not_ doing this is actually negligible (a single parked go thread).

There's also a couple lifecycle libraries that include Sente components:

  1. [danielsz/system] for use with [stuartsierra/component].
  2. [palletops/bakery] for use with [palletops/leaven].

If you do want a lifecycle management lib, I'm personally fond of Leaven since it's simpler (no auto dependencies) and adds ClojureScript support (which is handy for Sente).

#### Any other examples?

Here's some more unofficial/**user-submitted** examples for those interested! (**PRs welcome!**):

Example          | Author        | Comments                                         |
---------------- | ------------- | ------------------------------------------------ |
[om-mouse]       | [@tf0054]     | Basic example: tracks mouse pos using Om.        |

#### Any other questions?

If I've missed something here, feel free to open a GitHub issue or pop me an email!

## Contact & contributing

`lein start-dev` to get a (headless) development repl that you can connect to with [Cider][] (emacs) or your IDE.

Please use the project's GitHub [issues page][] for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com][] or on [Twitter][]. Cheers!

## License

Copyright &copy; 2012-2014 Peter Taoussanis. Distributed under the [Eclipse Public License][], the same as Clojure.

[API docs]: <http://ptaoussanis.github.io/sente/>
[CHANGELOG_]: <https://github.com/ptaoussanis/sente/blob/master/CHANGELOG.md>
[CHANGELOG]: <https://github.com/ptaoussanis/sente/releases>
[other Clojure libs]: <https://www.taoensso.com/clojure-libraries>
[Twitter]: <https://twitter.com/ptaoussanis>
[SemVer]: <http://semver.org/>
[Break Version]: <https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md>
[reference example project]: <https://github.com/ptaoussanis/sente/tree/master/example-project>
[Leiningen]: <http://leiningen.org/>
[CDS]: <http://clojure-doc.org/>
[ClojureWerkz]: <http://clojurewerkz.org/>
[issues page]: <https://github.com/ptaoussanis/sente/issues>
[commit history]: <https://github.com/ptaoussanis/sente/commits/master>
[Cider]: <https://github.com/clojure-emacs/cider>
[taoensso.com]: <https://www.taoensso.com>
[Eclipse Public License]: <https://raw2.github.com/ptaoussanis/sente/master/LICENSE>
[Go]: <http://en.wikipedia.org/wiki/Go_game>
[edn]: <https://github.com/edn-format/edn>
[http-kit]: <https://github.com/http-kit/http-kit>
[React]: <http://facebook.github.io/react/>
[Reagent]: <https://github.com/holmsand/reagent>
[Om]: <https://github.com/swannodette/om>
[Chord]: <https://github.com/james-henderson/chord>
[jetty7-websockets-async]: <https://github.com/lynaghk/jetty7-websockets-async>
[Socket.IO]: <http://socket.io/>
[om-mouse]: <https://git.geekli.st/tf0054/om-mouse/tree/master>
[@tf0054]: https://github.com/tf0054
[stuartsierra/component]: https://github.com/stuartsierra/component
[danielsz/system]: https://github.com/danielsz/system
[palletops/leaven]: https://github.com/palletops/leaven
[palletops/bakery]: https://github.com/palletops/bakery
