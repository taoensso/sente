> This project uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md) as of **Aug 16, 2014**.

## v1.7.0 - 2015 Dec 8

> As v1.7.0-RC1 with some updated dependencies, improved reference example

```clojure
[com.taoensso/sente "1.7.0"]
```


## v1.7.0-RC1 - 2015 Sep 28

> This is a significant non-breaking feature release. Includes an important fix for Immutant users.

* **New**: add `nginx-clojure` server adapter [#160 @xfeep]
* **New**: add `:error-handler` option to standard chsk routers
* **New**: `make-channel-socket!` now accepts an optional :params map [#158 #135 @danielcompton]
* **New**: include `:client-id` with Ajax long-polling requests [#155 @akhudek]
* **New**: `cb-error?` convenience fn = `(complement cb-success?)`
* **Fix**: Immutant v2.1.0+ adapter now supports timeouts to prevent lp socket leaks [#150 @tobias]

```clojure
[com.taoensso/sente "1.7.0-RC1"]
```


## v1.6.0 - 2015 Aug 7

> This is a significant maintenance+feature release which **MAY BE BREAKING** due to a mandatory dependency bump to Timbre v4 (see **note 1** for details).

* **BREAKING**: switch to Timbre v4 for cross-platform logging [**note 1**]
* **DEPRECATED**: `chsk-url-fn`, make cross-domain chsks easier to configure [#50 #136]
* **Implementation**: refactor default chsk-router
* **New**: add :uid to ev-msg's handled by Sente server [@danielcompton #147]
* **New**: add support for Transit packer opts [@estsauver #145]
* **New**: add option to leave :chsk/recv events unwrapped [#151]
* **New**: add client-side backoff opts [#125]
* **Fix**: switch to encore edn reader/writer (fix issue with printing large data structures under REPL)
* **Ref example**: add run command [@estsauver #144]

```clojure
[com.taoensso/sente "1.6.0"]
```

#### Notes

**[1]** Please see https://github.com/ptaoussanis/timbre/releases/tag/v4.0.0 for Timbre v4's **migration checklist**. Sorry for the hassle! This one-off change allows Sente to inherit all of Timbre's logging goodness (full logging config, ns filtering, production logging call elision, etc.). Migration usu. consists of a 1 or 2 line change if you're not using custom Timbre appenders.

## v1.5.0 - 2015 Jun 11

> This is a non-breaking maintenance release

* **New**: support Ajax CORS via new `:with-credentials?` opt [#130 @bplatz]
* **Fix**: bad missing-middleware error logging call format
* **Implementation**: update dependencies

```clojure
[com.taoensso/sente "1.5.0"]
```


## v1.4.1 - 2015 Mar 11

> Trivial, non-breaking release that adds a pair of optional web-adapter aliases to help make examples a little simpler.

```clojure
[com.taoensso/sente "1.4.1"]
```


## v1.4.0 - 2015 Mar 9

> This is a major **BREAKING** release. Biggest change is added support for web servers besides http-kit (only _Immutant_ for now). A big thanks to @tobias for his assistance with the Immutant support.

* **BREAK**: added support for web servers besides http-kit (**see migration instructions**) [@tobias #2]
* **BREAK**: removed support for `nil` user-id broadcasts (previously deprecated in v1.3.0) [#85] **[1]**
* **Fix**: temporary workaround for core.async router error-catching issues [@shaharz #97]
* **New**: throw clear compile-time exception on encore dependency issues
* **New**: enable clients to distinguish between auto/manual reconnects [@sritchie #105] **[2]**
* **New**: allow arbitrary user-provided handshake data with :chsk/handshake events [@whodidthis #110 #111] **[3]**
* **Ref example**: some clarifications re: how to authenticate users

```clojure
[com.taoensso/sente "1.4.0"]
```

#### Notes

**[1]**: Server-side `(chsk-send! <user-id> <event>)` calls used to broadcast to all nil-uid users when `<user-id>` was `nil`. Now you must use the special `:sente/all-users-without-uid` keyword for these cases. The new behaviour helps prevent unintentional broadcasting.

**[2]**: `:chsk/state` event data now contains `:requested-reconnect?` val.

**[3]**: Server-side `make-channel-socket!` fn now takes an optional `:handshake-data-fn (fn [ring-req])` opt and client-side's `ch-recv` now receives `[:chsk/handshake [<?uid> <?csrf-token> <?handshake-data>]]` events.


#### MIGRATION INSTRUCTIONS (from any version < v1.4.0)

 1. Http-kit is no longer an automatic Sente dependency. To continue using http-kit, add `[http-kit "2.1.19"]` to your project.clj `:dependencies`.
 2. Your Clojure-side `make-channel-socket!` call must now take a web server adapter as first argument. To continue using http-kit, add `[taoensso.sente.server-adapters.http-kit]` to your Clojure-side ns form's `:require` entries and pass `taoensso.sente.server-adapters.http-kit/http-kit-adapter` as the first arg to `make-channel-socket!`.

So:

```clojure
[http-kit "2.1.19"] ; <--- Add to project.clj :dependencies

(ns my-clj-ns
  (:require
    ;; Other stuff
    [taoensso.sente.server-adapters.http-kit] ; <--- Add this entry
    ))

;; (sente/make-channel-socket! <opts-map>) ; Old Clojure-side chsk constructor
(sente/make-channel-socket!
  taoensso.sente.server-adapters.http-kit/http-kit-adapter ; <--- Add this arg
  <opts-map) ; NEW Clojure-side chsk constructor
```

This change is a once-off nuisance that'll allow us the freedom of supporting a wide range of web servers in the future. Interested in a web server besides http-kit or Immutant? Am now [welcoming PRs](https://github.com/ptaoussanis/sente/issues/102) to support additional web servers.

Finally, **please see the updated [reference example project](https://github.com/ptaoussanis/sente/tree/master/example-project) for instructions on switching to an alternative web server like Immutant.**

/ Peter Taoussanis

----

## v1.3.0 / 2015 Jan 17

 > This is a non-breaking maintenance release focused on general housekeeping + on adding some user-id flexibility.

 * **DEPRECATED** [#85]: Server->user broadcasts should now use `:sente/all-users-without-uid` instead of `nil` uid when intending to broadcast to users _without_ a user id. The new behaviour is less accident prone.
 * **CHANGE** [#84, #95]: Improve error reporting in the case of missing Ring middleware.
 * **FIX** [#94]: ClojureScript dependency is now `:provided` to keep it from being unnecessarily pulled into JARs, etc (@zentrope).
 * **NEW** [#82]: Server-side `:user-id-fn`'s Ring request now includes a `:client-id` arg provided by clients.
 * Various doc+example improvements.


## v1.2.0 / 2014 Oct 6

> This is a maintenance release that is **non-breaking UNLESS**:
> 1. You are not using the default server-side chsk router.
> 2. You are relying on (`?reply-fn <args>)` to log a warning rather than throw an NPE for nil `?reply-fn`s.

 * **FIX**: Broken chsk router shutdown due to http://goo.gl/be8CGP.
 * **BREAKING** [#77]: No longer substitute a dummy (logging) `?reply-fn` for non-callback events.


## v1.1.0 / 2014 Sep 7

 * **FIX**: https://github.com/ptaoussanis/timbre/issues/79 (unnecessary deps being pulled in).
 * **NEW**: Added client-side `ajax-call` utility.
 * **NEW**: Added keys to `event-msg`s: `:id` (event-id), `:?data` (event-?data).


## v1.0.0 / 2014 Sep 2

 > This is a MAJOR release with a bunch of improvements, most notably efficiency improvements. It is BREAKING if-and-only-if you read from the client-side :ch-recv channel directly.

 * **NEW**: Added `chsk-destroy!` client-side API fn.
 * **NEW** [#60]: Several transfer format efficiency improvements (faster, less bandwidth use).
 * **NEW** [#12], [#59], [#66], [#67]: Added `:packer` option to client+server-side `make-channel-socket!` fns. This can be used to plug in an arbitrary de/serialization format. The default continues to be edn (which gives the best common-case performance and doesn't require any extra dependencies). An experimental Transit-based packer is included which allows manual + smart (automatic) per-payload format selection. See the updated reference example for details. Big thanks to @ckarlsen for getting the work started on this!
 * **DEPRECATED**: `start-chsk-router-loop!`->`start-chsk-router!` (both client + server-side). There's a new event-handler format that's consistent between the client + server, and that makes componentizing Sente considerably easier. See the updated reference example for details. Big thanks to @hugoduncan for his work & input on this!
 * **CHANGE**: Client-side router now traps+logs errors like the server-side router.
 * **CHANGE**: General code improvements/refactoring, stuff I'd been meaning to do forever and wanted to get in before a v1 release.
 * **CHANGE**: Further improvements to the reference example to make it play better with LightTable.
 * **BREAKING**: the client-side `:ch-recv` channel now receives `event-msg` (maps) rather than `event` (vectors). `(:event <event-msg>)` will return the `event-msg`'s `event`.


## v0.15.1 / 2014 July 21

 > Hotfix focused on adjusting default options for Heroku users.

 * **FIX** [#56]: Decrease default keep-alive values to work with [Heroku's http-routing timeouts](https://devcenter.heroku.com/articles/http-routing#timeouts).
 * Minor reference example improvements.


## v0.15.0 / 2014 July 11

 > Minor, non-breaking update.

 * **CHANGE**: Support for new (namespaced) [Ring Anti-Forgery](https://github.com/ring-clojure/ring-anti-forgery/commit/69082e6eac533a0c62c8418c78684030eeefbcec) session key (@DomKM).
 * **CHANGE**: `[chsk/uidport-close]` event now triggers only 5 seconds after a WebSocket channel is closed (same as Ajax channels). Helps prevent unnecessary noise during window refresh, etc.
 * **NEW** [#50]: Added a `:chsk-url-fn` option to client-side `make-channel-socket!` fn for full URL control.


## v0.14.1 / 2014 May 18

 > Minor, non-breaking update.

 * **FIX**: Handshakes were interfering with singleton WebSocket callback replies.


## v0.14.0 / 2014 May 16

 > Minor, non-breaking update.

 * **FIX**: WebSocket reconnect exponential backoff wasn't being reset correctly.
 * [#39] **FIX**: Race condition affecting buffered `server>user` events (@smichal).
 * **NEW**: `[chsk/uidport-open]`, `[chsk/uidport-close]` server-side events generated on a uid connecting/disconnecting (any protocol). As before, you can watch the `connected-uids` atom for more detailed info.


## v0.13.0 / 2014 May 8

 > This is a **major** release focused on simpler out-the-box setup + easier integration with a wide range of login/auth types.

 * **BREAKING**: API fns removed: `chsk-type`, `chsk-open?`.
 * **BREAKING**: The `[:chsk/state]` event form has changed for added flexibility.
 * **NEW**: Added watchable, read-only `:state` atom to client-side `make-channel-socket!` fn result. Among other things, this atom contains any user-id provided by the server.
 * **NEW**: It is now possible to push server>user async events to clients _without_ a user-id by providing a `nil` user-id to the server-side `chsk-send!` fn (previously `nil` uids would throw an assertion error). In particular, this means it's now possible to broadcast to users that aren't logged in.
 * **NEW**: Server-side `make-channel-socket!` fn has picked up a `:csrf-token-fn` option which defaults to compatibility with the [Ring-Anti-Forgery](https://github.com/ring-clojure/ring-anti-forgery) middleware.
 * **NEW**: Clients are now entirely self configuring. It's no longer necessary to transfer any state (like csrf-token or user-id) from the server; this'll be done automatically on channel socket handshake.
 * **NEW**: Added a `chsk-reconnect!` API method that can be called to easily re-establish a channel socket connection after a login or auth change. **An example login procedure** has been added to the reference example project.
 * **CHANGE**: The example project now randomly selects `:ajax` or `:auto` connection mode.

As always, feedback welcome on any changes here. Have fun, cheers! - Peter


## v0.12.0 / 2014 May 1

 * **NEW**: server- and client-side `start-chsk-router-loop!` fns now return a `(fn stop! [])`.
 * [#37] **FIX** broken `[:chsk/close]` typo for Ajax connections (@sritchie).


## v0.11.0 / 2014 Apr 26

 * **CHANGE**: Removed vestigial server-side events: `[:chsk/uidport-open _]`, `[:chsk/uidport-close _]`.
 * **CHANGE**: Significantly improved Ajax broadcast performance by interally making use of `connected-uids` data.
 * **NEW**: `[:chsk/close]` event can now be sent to clients to disconnect them (this feature was previously experimental + undocumented).
 * **FIX**: `connected-uids` was incorrectly marking multi-client users as disconnected when any _one_ of their clients disconnected.


## v0.10.1 / 2014 Apr 17

 * [#27] **FIX** broken :advanced mode compilation (**@ostronom**).


## v0.10.0 / 2014 Apr 17

 * **BREAKING CHANGE**: ClojureScript (client-side) `make-channel-socket!` fn signature has **changed**:
```clojure
;; OLD (note two opts maps):
(make-channel-socket! {:csrf-token "foo" :has-uid? true} {:type :auto}) ; Old
;; NEW (note single opts map):
(make-channel-socket! {:csrf-token "foo" :has-uid? true :type :auto}) ; New
```

 * [#22] **NEW**: Server-side `make-channel-socket!` constructor now supports an optional `:user-id-fn` `(fn [ring-req]) -> user-id` setting (@sritchie).
 * [#23] **NEW**: Server-side `make-channel-socket!` now returns a `:connected-uids` atom.


## v0.9.0 / 2014 Mar 29

 > This is a **non-breaking** release focused on efficiency+reliability improvements for very high stress environments.

 * Documentation improvements.
 * **CHANGE**: server>user Ajax push is now more reliable against dodgy connections.
 * **NEW**: server>user sends are now automatically+transparently batched for greater efficiency in _very_ high throughput environments. The server-side `make-channel-socket!` has picked up some knobs for this, but the defaults are sensible.


## v0.8.2 / 2014 Mar 7

 * **NEW**: Copy improved error messages to server-side API.
 * **CHANGE**: Provide entire, unfiltered Ring request map to server-side API.


## v0.8.1 / 2014 Mar 4

 * **NEW**: Improved error messsages for malformed events.


## v0.8.0 / 2014 Feb 24

 * **NEW**: Initial public release.
