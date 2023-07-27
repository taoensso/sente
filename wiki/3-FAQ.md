# What is the `user-id` provided to the server>user push fn?

For the server to push events, we need a destination. Traditionally we might push to a _client_ (e.g. browser tab). But with modern rich web applications and the increasing use of multiple simultaneous devices (tablets, mobiles, etc.) - the value of a _client_ push is diminishing. You'll often see applications (even by Google) struggling to deal with these cases.

Sente offers an out-the-box solution by pulling the concept of identity one level higher and dealing with unique _users_ rather than clients. **What constitutes a user is entirely at the discretion of each application**:

 * Each user-id may have zero _or more_ connected clients at any given time
 * Each user-id _may_ survive across clients (browser tabs, devices), and sessions

**To give a user an identity, either set the user's `:uid` Ring session key OR supply a `:user-id-fn` (takes request, returns an identity string) to the `make-channel-socket-server!` constructor.**

If you want a simple _per-session_ identity, generate a _random uuid_. If you want an identity that persists across sessions, try use something with _semantic meaning_ that you may already have like a database-generated user-id, a login email address, a secure URL fragment, etc.

> Note that user-ids are used **only** for server>user push. client>server requests don't take a user-id.

See [here](./2-Client-and-user-ids) for more info.

# How do I integrate Sente with my usual login/auth procedure?

This should be pretty easy to do, please see one of the [example projects](./3-Example-projects) for details!

# Will Sente work with Reactjs/Reagent/Om/Pedestel/etc.?

Sure! Sente's just a client<->server comms mechanism so it'll work with any view/rendering approach you'd like.

I have a strong preference for [Reagent](https://reagent-project.github.io/) myself, so would recommend checking that out first if you're still evaluating options.

# What if I need to use JSON, XML, raw strings, etc.?

Sente uses an extensible client<->server serialization mechanism. It uses edn by default since this usu. gives good performance and doesn't require any external dependencies. The [reference example project](./3-Example-projects#reference-example) shows how you can plug in an alternative de/serializer. In particular, note that Sente ships with a Transit de/serializer that allows manual or smart (automatic) per-payload format selection.

# How do I add custom Transit read and write handlers?

To add custom handlers to the TransitPacker, pass them in as `writer-opts` and `reader-opts` when creating a `TransitPacker`. These arguments are the same as the `opts` map you would pass directly to `transit/writer`. The code sample below shows how you would do this to add a write handler to convert [Joda-Time](https://www.joda.org/joda-time/) `DateTime` objects to Transit `time` objects.

```clj
(ns my-ns.app
  (:require [cognitect.transit :as transit]
            [taoensso.sente.packers.transit :as sente-transit])
  (:import [org.joda.time DateTime ReadableInstant]))

;; From https://increasinglyfunctional.com/2014/09/02/custom-transit-writers-clojure-joda-time.html
(def joda-time-writer
  (transit/write-handler
    (constantly "m")
    (fn [v] (-> ^ReadableInstant v .getMillis))
    (fn [v] (-> ^ReadableInstant v .getMillis .toString))))

(def packer (sente-transit/->TransitPacker :json {:handlers {DateTime joda-time-writer}} {}))
```

# How do I route client/server events?

However you like! If you don't have many events, a simple `cond` will probably do. Otherwise a multimethod dispatching against event ids works well (this is the approach taken in the [reference example project](./3-Example-projects#reference-example)).

# Security: is there HTTPS support?

Yes, it's automatic for both Ajax and WebSockets. If the page serving your JavaScript (ClojureScript) is running HTTPS, your Sente channel sockets will run over HTTPS and/or the WebSocket equivalent (WSS).

# Security: CSRF protection?

**This is important**. Sente has support, and use is **strongly recommended**. You'll need to use middleware like [ring-anti-forgery](https://github.com/ring-clojure/ring-anti-forgery) or [ring-defaults](https://github.com/ring-clojure/ring-defaults) to generate and check CSRF codes. The `ring-ajax-post` handler should be covered (i.e. protected).

Please see one of the [example projects](./3-Example-projects) for a fully-baked example.

# Pageload: How do I know when Sente is ready client-side?

You'll want to listen on the receive channel for a `[:chsk/state [_ {:first-open? true}]]` event. That's the signal that the socket's been established.

# How can server-side channel socket events modify a user's session?

Recall that server-side `event-msg`s are of the form `{:ring-req _ :event _ :?reply-fn _}`, so each server-side event is accompanied by the relevant[*] Ring request.

> For WebSocket events this is the initial Ring HTTP handshake request, for Ajax events it's just the Ring HTTP Ajax request.

The Ring request's `:session` key is an immutable value, so how do you modify a session in response to an event? You won't be doing this often, but it can be handy (e.g. for login/logout forms).

You've got two choices:

1. Write any changes directly to your Ring SessionStore (i.e. the mutable state that's actually backing your sessions). You'll need the relevant user's session key, which you can find under your Ring request's `:cookies` key. This is flexible, but requires that you know how+where your session data is being stored.

2. Just use regular HTTP Ajax requests for stuff that needs to modify sessions (like login/logout), since these will automatically go through the usual Ring session middleware and let you modify a session with a simple `{:status 200 :session <new-session>}` response. This is the strategy the reference example takes.

[@danielsz](https://github.com/danielsz) has kindly provided a detailed example [here](../issues/62#issuecomment-58790741).

# Lifecycle management (component management/shutdown, etc.)

Using something like [@stuartsierra/component](https://github.com/stuartsierra/component) or [@palletops/leaven](https://github.com/palletops/leaven)?

Most of Sente's state is held internally to each channel socket (the map returned from client/server calls to `make-channel-socket!`). The absence of global state makes things like testing, and running multiple concurrent connections easy. It also makes integration with your component management easy.

The only thing you _may_[1] want to do on component shutdown is stop any router loops that you've created to dispatch events to handlers. The client/server side `start-chsk-router!` fns both return a `(fn stop [])` that you can call to do this.

> [1] The cost of _not_ doing this is actually negligible (a single parked go thread).

There's also a couple lifecycle libraries that include Sente components:

 1. [@danielsz/system](https://github.com/danielsz/system) for use with [@stuartsierra/component](https://github.com/stuartsierra/component)
 2. [@palletops/bakery](https://github.com/palletops/bakery) for use with [@palletops/leaven](https://github.com/palletops/leaven)

# How to debug/benchmark Sente at the protocol level?

[@arichiardi](https://github.com/arichiardi) has kindly provided some notes on this [here](./4-Connection-debugging).