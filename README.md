**[API docs][]** | **[CHANGELOG][]** | [other Clojure libs][] | [Twitter][] | [contact/contributing](#contact--contributing) | current ([semantic][]) version:

```clojure
[com.taoensso/sente "0.8.0-SNAPSHOT"] ; Experimental
```

# Sente, channel sockets for Clojure

> **Sen-te** (先手) is a Japanese [Go][] term used to describe a play with such an overwhelming follow-up that it forces an immediate response, thus leaving its player with the initiative.

**Sente** is small client+server library that makes it easy to build **reliable, high-performance realtime web applications with Clojure**.

Or: **The missing piece in Clojure's web application story**  
Or: **We don't need no Socket.IO**  
Or: **Clojure(Script) + core.async + WebSockets/Ajax = _The Shiz_**

## What's in the box™?
  * **Bidirectional a/sync comms** over both **WebSockets** and **Ajax** (auto-selecting).
  * **Robust**: auto keep-alives, buffering, mode fallback, reconnects. **It just works™**.
  * [edn][] rocks. So **send edn, get edn**: no json here.
  * **Tiny, simple API**: `make-channel-socket!` and you're good to go.
  * Automatic, sensible support for users connected with **multiple clients** and/or devices simultaneously.
  * **Flexible model**: use it anywhere you'd use WebSockets or Ajax.
  * **Fully documented, with examples** (more forthcoming).
  * Small: **less than 500 lines of code** for the entire client+server implementation.
  * **Supported servers**: currently only [http-kit][], but easily extended. [PRs welcome](https://github.com/ptaoussanis/sente/issues/2) to add support for additional servers!


### Capabilities

Protocol            | client>server | client>server + ack/reply | server>clientS push |
------------------- | ------------- | ------------------------- | ------------------- |
WebSockets          | ✓ (native)    | ✓ (emulated)              | ✓ (native)          |
Ajax                | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)        |

So the underlying protocol's irrelevant. Sente gives you a unified API that exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional evented ack/reply model).


## Getting started

Add the necessary dependency to your [Leiningen][] `project.clj`. This'll provide your project with both the client (ClojureScript) + server (Clojure) side library code:

```clojure
[com.taoensso/sente "0.8.0-SNAPSHOT"]
```

### On the server (Clojure) side

First, make sure you're using [http-kit][] as your Clojure web server. If you're using the standard Ring server (Jetty), http-kit is [almost](http://http-kit.org/migration.html) a drop-in replacement. The examples here will also use [core.match](https://github.com/clojure/core.match).

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
    [clojure.core.match :as match :refer (match)] ; Optional, useful
    [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop))]
    [taoensso.sente :as sente] ; <--- Add this
   ))

;;; Add this: --->
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv)
  (def chsk-send!                    send-fn))

(defroutes my-app
  ;; <other stuff>

  ;;; Add these 2 entries: --->
  (GET  "/chsk" req (#'ring-ajax-get-or-ws-handshake req)) ; Note the #'
  (POST "/chsk" req (#'ring-ajax-post                req)) ; ''

  )
```

> The `ring-ajax-post` and `ring-ajax-get-or-ws-handshake` fns will automatically handle Ring GET and POST requests to our channel socket URL (`"/chsk"`). Together these take care of the messy details of establishing + maintaining WebSocket or long-polling requests.

### On the client (ClojureScript) side

You'll setup something similar on the client side:

```clojure
(ns my-client-side-ns ; .cljs
  (:require-macros
   [cljs.core.match.macros :refer (match)] ; Optional, useful
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
   ;; <other stuff>
   [cljs.core.match] ; Optional, useful
   [cljs.core.async :as async :refer (<! >! put! chan)]
   [taoensso.sente :as sente :refer (cb-success?)] ; <---- Add this
  ))

;;; Add this: --->
(let [{:keys [chsk ch-recv send-fn]}
      (chsks/make-channel-socket! "/chsk" ; Note the same URL as before
       {} {:type :auto ; e/o #{:auto :ajax :ws}})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))
```

### Now what?

You're good to go! The client will automatically initiate a WebSocket or repeating long-polling connection to your server.

#### Client-side API

 * `ch-recv` is a **core.async channel** that'll receive **`event`**s.
 * `chsk-send!` is a `(fn [event & [?timeout-ms ?cb-fn]])`.

#### Server-side API

 * `ch-recv` is a **core.async channel** that'll receive **`event-msg`**s.
 * `chsk-send!` is a `(fn [user-id event])`.

===============

Term          | Form                                                             |
------------- | ---------------------------------------------------------------- |
**event**     | `[<ev-id> <?ev-data>]`, e.g. `[:my-app/some-req {:data "data"}]` |
**event-msg** | `{:ring-req _ :event _ :?reply-fn _}`                            |
`<ev-id`>     | A _namespaced_ keyword like `:my-app/some-req`                   |
`<?ev-data>`  | An optional _arbitrary edn value_ like `{:data "data"}`          |

#### Summary

  * So clients can use `chsk-send!` to send `event`s to the server. They can optionally request a reply, with timeout.
  * The server can likewise use `chsk-send!` to send `event`s to _all_ the clients (browser tabs, devices, etc.) of a particular connected user by his/her `user-id`.
  * The server can also use an `event-msg`'s `?reply-fn` to _reply_ to a client `event` using an _arbitrary edn value_.

===============

**And that's 80% of what you need to know to get going**. The remaining documentation is mostly for fleshing out the new patterns that this API enables.


### An example of event routing using core.match

```clojure
;;;; Server-side (.clj), in `my-server-side-routing-ns` ------------------------

(defn- event-msg-handler
  [{:as ev-msg :keys [ring-req event ?reply-fn]} _]
  (let [session (:session ring-req)
        uid     (:uid session)
        [id data :as ev] event]

    (timbre/debugf "Event: %s" ev)
    (match [id data]

     [:foo/bar _] ; Events matching [:foo/bar <anything>] shape
     (do (do-some-work!)
         (?reply-fn (str "Echo: " event))) ; Reply with a string

     [:my-app/request-fruit fruit-name]
     (?reply-fn {:some-data-key "some-data-val"
                 :your-fruit fruit-name}) ; Reply with a map

     :else
     (do (warnf "Unmatched event: %s" ev)
         (when-not (:dummy-reply-fn (meta ?reply-fn)) ; not `reply!`
           (?reply-fn (format "Unmatched event, echo: %s" ev)))))))

(sente/start-chsk-router-loop! event-msg-handler ch-chsk)
```

```clojure
;;;; Client-side (.cljs), in `my-client-side-ns` -------------------------------

(defn- event-handler [[id data :as ev] _]
  (logf "<! %s" id)
  (match [id data]

   ;; An event from `ch-ui` that our UI has generated:
   [:on.keypress/div#msg-input _] (do-something!)

   ;; A channel socket event pushed from our server:
   [:chsk/recv [:my-app/alert-from-server payload]]
   (do (logf "Payload pushed from server!: %s" payload)
       (do-something! payload))

   [:chsk/state [:first-open _]] (logf "Channel socket successfully established!")

   [:chsk/state new-state] (logf "Chsk state change: %s" new-state)
   [:chsk/recv  payload]   (logf "From server: %s"       payload)
   :else (logf "Unmatched <!: %s" id)))

(let [ch-chsk   ch-chsk ; Chsk events (incl. async events from server)
      ch-ui     (chan)  ; Channel for UI events, etc.
      ch-merged (async/merge [ch-chsk ch-ui])]
 (sente/start-chsk-router-loop! event-handler ch-merged))
```

### FAQ

#### What is Sente useful for?

[Single-page web applications](http://en.wikipedia.org/wiki/Single-page_application), realtime web applications, web applications that need to support efficient, high-performance push-to-client capabilities.

Sente's channel sockets are a **full replacement for both traditional WebSockets and Ajax** that is IMO:

  * More flexible.
  * Easier+faster to work with (esp. for rapid prototyping).
  * More efficient in most cases, and never less efficient.

#### Any disadvantages?

I've been using something similar to Sente in production for several months, but this particular public implementation is relatively immature. There aren't currently any known security holes, but I wouldn't rule out big or small bugs in the short term.

#### What is the `user-id` used by the server-side `chsk-send!` fn?

This is something your login procedure must handle. **Sente assumes logged-in users carry a unique `:uid` key in their Ring session**. This could be a username, a unique integer, a uuid string, etc.

#### CSRF security?

**This is important**. Sente has support, but you'll need to do a couple things on your end:

  1. Server-side: you'll need to use middleware like `ring-anti-forgery` to generate and check CSRF codes. The `ring-ajax-post` handler should be covered (i.e. protected).
  2. Client-side: you'll need to pass the page's csrf code to the `make-channel-socket!` constructor.

#### Why isn't `x` documented?

Sorry, just haven't had the time (yet)! Am currently in the process of launching a couple products and only released Sente now to de-stress and take a couple days off work. It was a case of releasing what I could put together in a weekend, or not releasing anything. **PR's are very welcome for any improvements, incl. to documentation+examples**!

If you have a question you might also want to take a look at the source code which is short + quite approachable. Otherwise feel free to open an issue and I'll try reply ASAP.

#### Will Sente work with [React](http://facebook.github.io/react/)/[Reagent](https://github.com/holmsand/reagent)/[Om](https://github.com/swannodette/om)/etc.?

Yup. Sente's just a client<->server message mechanism, it's completely unopinionated about the shape or architecture of your application.


## This project supports the CDS and ![ClojureWerkz](https://raw.github.com/clojurewerkz/clojurewerkz.org/master/assets/images/logos/clojurewerkz_long_h_50.png) goals

  * [CDS][], the **Clojure Documentation Site**, is a **contributer-friendly** community project aimed at producing top-notch, **beginner-friendly** Clojure tutorials and documentation. Awesome resource.

  * [ClojureWerkz][] is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing. They've got a ton of great stuff, check 'em out!

## Contact & contributing

Please use the project's GitHub [issues page][] for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com][] or on [Twitter][]. Cheers!

## License

Copyright &copy; 2012-2014 Peter Taoussanis. Distributed under the [Eclipse Public License][], the same as Clojure.


[API docs]: <http://ptaoussanis.github.io/sente/>
[CHANGELOG]: <https://github.com/ptaoussanis/sente/blob/master/CHANGELOG.md>
[other Clojure libs]: <https://www.taoensso.com/clojure-libraries>
[Twitter]: <https://twitter.com/ptaoussanis>
[semantic]: <http://semver.org/>
[Leiningen]: <http://leiningen.org/>
[CDS]: <http://clojure-doc.org/>
[ClojureWerkz]: <http://clojurewerkz.org/>
[issues page]: <https://github.com/ptaoussanis/sente/issues>
[commit history]: <https://github.com/ptaoussanis/sente/commits/master>
[taoensso.com]: <https://www.taoensso.com>
[Eclipse Public License]: <https://raw2.github.com/ptaoussanis/sente/master/LICENSE>
[Go]: <http://en.wikipedia.org/wiki/Go_game>
[edn]: <https://github.com/edn-format/edn>
[http-kit]: <https://github.com/http-kit/http-kit>
