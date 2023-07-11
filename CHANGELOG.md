This project uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md).

## `1.19.0` (2023-07-13)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.19.0)

This is intended as a **non-breaking maintenance release**, but it touches a lot of code so **please keep an eye out** for (and let me know about) any unexpected problems - thank you! 🙏

**Tip**: the [reference example](https://github.com/ptaoussanis/sente/tree/master/example-project) includes a number of tools to help test Sente in your environment.

### Fixes since `1.18.1`

* 0dc8a12 [fix] [#431] Some disconnected user-ids not removed from `connected-uids`

### New since `1.18.1`

* e330ef2 [new] Allow WebSocket constructors to delay connection
* 6021258 [new] [example] Misc improvements to example project
* d0fd918 [new] Alias client option: `:ws-kalive-ping-timeout-ms` -> `:ws-ping-timeout-ms`
* GraalVM compatibility is now tested during build


## `1.18.1` (2023-07-04)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.18.1)

This is an important **hotfix release**, please update if you're using `1.18.0`.

### Fixes since `1.18.0`

* ad62f1e [fix] Ajax poll not properly timing out
* 1d15fe5 [fix] [#430] `[:chsk/uidport-close]` server event not firing

### New since `1.18.0`

* 5c0f4ad [new] [example] Add example server-side uidport event handlers


## `1.18.0` (2023-06-30)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.18.0)

Same as `1.18.0-RC1`, except for:

* 7889a0b [fix] [#429] Bump deps, fix possible broken cljs builds


## `1.18.0-RC1` (2023-05-30)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.18.0-RC1)

This is a **major pre-release** that **INCLUDES BREAKING CHANGES**.

Please test carefully and **report any issues**!

### ⚠️ Changes since `1.17.0`

See [here](https://github.com/ptaoussanis/sente/wiki/Migration-instructions) for detailed **migration/upgrade instructions**! 👈

* 0b37e4c [mod] [#319] [BREAKING] Change default `wrap-recv-evs?` value
* d5b3dc5 [mod] [#404] [#398] [BREAKING] Disable default legacy pack value
* 23d9f7a [mod] [#412] [BREAKING] Move unofficial adapters under `community` dir
* c3d7c6c [mod] [#424] [BREAKING] Temporarily remove `jetty9-ring-adapter` support
* bfa4c72 [mod] Client will now re-connect on WebSocket send error
* 76b8abc [mod] [Aleph adapter] [#350] Experimental change to support Ring middleware (@g7s)
* 728901a [mod] [Undertow adapter] [#409] Add default Ajax read timeout (@kajism)
* 8806e72 [new] [Undertow adapter] [#409] Allow Ajax read timeout (@kajism)
* e6cdf99 [mod] Refactor, improve logging

### Fixes since `1.17.0`

* eae2726 [fix] [#259] Improve client-side detection of broken connections
* a2b9af8 [fix] [#417] Fix broken server->client broadcast on client IP change (@Naomarik)
* 82fc83d [fix] Verify expected server-ch identity when updating conns
* c6deca6 [fix] Potential (though unlikely) race condition on client GC
* 7b466ad [fix] [#260] NB Prevent unnecessary participation of Ajax channels in `conns_` during handshake
* cc84303 [fix] [new] [#380] NB Refactor ws state management
* da73f03 [fix] [#346] [#340] Malformed `:chsk/bad-package` error
* 91a239b [fix] [#428] Unpack broken for binary data (@rosejn)
* Several fixes to Undertow adapter (2a91ad4, 318e90a, a4cf644)

### New since `1.17.0`

* 7dba037 [new] [#420] More reliable WebSocket server->client broadcasts
* 5f945db [new] [#414] Add server config option to control msecs before marking conns as closed
* 6f3e521 [new] [#259] Add client config option to control kalive ping timeout
* f560294 [new] [#325] Add option for custom WebSocket constructor
* ddde20d [new] [#342] Smarter Ajax XHR acquisition, opt to control pool
* 45e1880 [new] [#422] Add client-side util to simulate a broken connection
* 627029f [new] [Experimental] Add support for 3-arity (async) Ring v1.6+ handlers
* 221f112 [new] [Example] Update, improve reference example
* Introduced a new [community docs wiki](https://github.com/ptaoussanis/sente/wiki)

### Other improvements since `1.17.0`

* 057a8cb [new] Add interface docstrings
* c6aca8c [nop] [#406] Clarify client+server docstrings re: csrf-token
* 8b9804e [nop] Mark deprecated vars
* Major improvements to [example project](https://github.com/ptaoussanis/sente/tree/master/example-project)
* Many other small improvements to implementation, documentation, etc.


## v1.17.0 - 2022 Jun 13

```clojure
[com.taoensso/sente "1.17.0"]
```

> This is a non-breaking **maintenance and feature release**.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

Identical to `v1.17.0-RC2`.

### Changes since `1.16.2`

- Updated dependencies

### New since `1.16.2`

- [#398] Enable binary support for custom un/packers (@rosejn, @ptaoussanis)
- [#351 #393][New] Allow CSRF-token to be a function (@eneroth, @ptaoussanis)
- [#390] [Aleph adapter] Add support for websocket-connection options (@KaliszAd)
- [#396][Readme] Add link to Retit+JS example (@dharrigan)
- [#395][Readme] Add link to new SPA example (@FiV0)

### Fixes since `1.16.2`

- [#403][Fix] Start ws-kalive loop only after conn is established (@Snurppa)


## v1.17.0-RC2 - 2022 Jun 1

```clojure
[com.taoensso/sente "1.17.0-RC2"]
```

> This is a non-breaking **maintenance and feature release**.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

### Changes since `1.16.2`

- Updated dependencies

### New since `1.16.2`

- [#398] Enable binary support for custom un/packers (@rosejn, @ptaoussanis)
- [#351 #393][New] Allow CSRF-token to be a function (@eneroth, @ptaoussanis)
- [#390] [Aleph adapter] Add support for websocket-connection options (@KaliszAd)
- [#396][Readme] Add link to Retit+JS example (@dharrigan)
- [#395][Readme] Add link to new SPA example (@FiV0)

### Fixes since `1.16.2`

- [#403][Fix] Start ws-kalive loop only after conn is established (@Snurppa)


## v1.16.2 - 2021 Feb 26

```clojure
[com.taoensso/sente "1.16.2"]
```

> This is a non-breaking **minor maintenance release**.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

### New since `1.16.1`

- Add public `ring-allow-origin?` util fn

### Changes since `1.16.1`

- Updated dependencies (non-breaking)


## v1.16.1 - 2021 Jan 31

```clojure
[com.taoensso/sente "1.16.1"]
```

> This is a **minor maintenance release**.  
> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

### Fixes since `1.16.0`

- [#385] Fix: pass ring-req to authorized?-fn (@viesti)

### New since `1.16.0`

- chsk server: add `:?unauthorized-fn` option


## v1.16.0 - 2020 Sep 19

```clojure
[com.taoensso/sente "1.16.0"]
```

> Major feature release. Should be non-breaking, but see [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.

Same as `v1.16.0-RC1`, `v1.16.0-alpha2`.

#### Changes since `v1.15.0`

* **[NB]** http-kit users must now use >= http-kit [`v2.4.0`](https://github.com/http-kit/http-kit/releases/tag/v2.4.0) ([`v2.5.0`](https://github.com/http-kit/http-kit/releases/tag/v2.5.0) is latest as of writing)

#### New since `v1.15.0`

* [#371 #375] Add Jetty 9 server adapter (@wavejumper)
* [#372] Add `ring-undertow` server adapter (@nikolap)
* [#275 #374] Add Clj WebSocket client support (@kaosko)
* Add optional auth fn to `make-channel-socket-server!` (@kaosko @ptaoussanis)
* [#356] Expose `send-buffers_` as implementation detail (@kaosko)
* [#359 #360] Add :json-verbose format to Transit packer (@p-himik)
* [#362 #363] Allow additional keys in event-msg maps (@jjttjj)
* [#365] README: incl. example CSRF code (@mattford63)

#### Fixes since `v1.15.0`

* [#366 #353 #358] Make make-channel-socket-client! respect host option (@Rkiouak)
* Use new http-kit v2.4.0 server API internally to fix possible [race conditions](https://github.com/http-kit/http-kit/issues/318)
* [#357 #247] Fix for React Native (@currentoor)


## v1.16.0-RC1 - 2020 Sep 10

```clojure
[com.taoensso/sente "1.16.0-RC1"]
```

Same as `v1.16.0-alpha2`.

> See [here](https://github.com/ptaoussanis/encore#recommended-steps-after-any-significant-dependency-update) for recommended steps when updating any Clojure/Script dependencies.


## v1.16.0-alpha2 - 2020 Aug 24

```clojure
[com.taoensso/sente "1.16.0-alpha2"]
```

> Major feature release. _Should_ be non-breaking, but users of http-kit **will need to update to >= [http-kit v2.4.0](https://github.com/http-kit/http-kit/releases/tag/v2.4.0)**.

#### Tip! Recommended steps after any significant dependency update:

1. Run `lein deps :tree` (or equivalent) to check for possible dependency conflicts.
2. Run `lein clean` (or equivalent) to ensure no stale build artifacts remain.
3. Please test carefully before running in production!

Some info on how to resolve dependency conflicts [here](https://github.com/ptaoussanis/encore/blob/master/DEP-CONFLICT.md).

#### Changes since `v1.15.0`

* **[NB]** http-kit users must now use >= http-kit v2.4.0.

#### New since `v1.15.0`

* [#371 #375] Add Jetty 9 server adapter (@wavejumper).
* [#372] Add `ring-undertow` server adapter (@nikolap).
* [#275 #374] Add Clj WebSocket client support (@kaosko).
* Add optional auth fn to `make-channel-socket-server!` (@kaosko @ptaoussanis).
* [#356] Expose `send-buffers_` as implementation detail (@kaosko).
* [#359 #360] Add :json-verbose format to Transit packer (@p-himik).
* [#362 #363] Allow additional keys in event-msg maps (@jjttjj).
* [#365] README: incl. example CSRF code (@mattford63).

#### Fixes since `v1.15.0`

* [#366 #353 #358] Make make-channel-socket-client! respect host option (@Rkiouak).
* Use new http-kit v2.4.0 server API internally to fix possible [race conditions](https://github.com/http-kit/http-kit/issues/318).
* [#357 #247] Fix for React Native (@currentoor).


## v1.15.0 - 2019 Nov 27

```clojure
[com.taoensso/sente "1.15.0"]
```

> Just updates some dependencies. Should be non-breaking.

* [#355] **Fix**: Bump encore dependency to fix deprecated `goog.structs/Map` issue.


## v1.14.0 - 2019 Oct 19

```clojure
[com.taoensso/sente "1.14.0"]
```

As `v1.14.0-RC2`, but also includes:

* [#307] **New**: Add server adapter for Macchiato Framework on Node.js (@theasp)
* [#137 #338] **New**: Add support for origin/referrer checking (@eerohele)
* [#349 #348] **New**: Add support for specifying chsk port when connecting from client (@pieterbreed)
* [#337] **Fix**: Incorrect value (only udt) swapped into conns_ (@osbert)
* [#341] **Fix**: Make cljsbuild output-to resources/public/main.js directly (@shaolang)


## v1.14.0-RC2 - 2019 Jan 12

```clojure
[com.taoensso/sente "1.14.0-RC2"]
```

> This is a **CRITICAL** bugfix release, please upgrade ASAP

* [#137] **SECURITY FIX, BREAKING**: fix badly broken CSRF protection (@danielcompton, @awkay, @eerohele), more info below

> My sincere apologies for this mistake. Please write if I can provide more details or any other assistance. Further testing/auditing/input very much welcome! - @ptaoussanis

### Security bug details

- All previous versions of Sente (< v1.14.0) contain a critical security design bug identified and reported by @danielcompton, @awkay, @eerohele. (Thank you to them for the report!).
- **Bug**: Previous versions of Sente were leaking the server-side CSRF token to the client during the (unauthenticated) WebSocket handshake process.
- **Impact**: An attacker could initiate a WebSocket handshake against the Sente server to discover a logged-in user's CSRF token. With the token, the attacker could then issue cross-site requests against Sente's endpoints. Worse, since Sente often shares a CSRF token with the rest of the web server, it may be possible for an attacker to issue **cross-site requests against the rest of the web server** (not just Sente's endpoints).

### Security fix details

- The fix [commit](https://github.com/ptaoussanis/sente/commit/ae3afd5cf92591c9f756c3177142bee7cccb8b6b) stops the CSRF token leak, introducing a **BREAKING API CHANGE** (details below).
- Sente will now (by default) refuse to service any requests unless a CSRF token is detected (e.g. via `ring-anti-forgery`).

### Breaking changes

#### `make-channel-socket-client!` now takes an extra mandatory argment

It now takes an explicit `csrf-token` that you must provide. The value for the token can be manually extracted from the page HTML ([example](https://github.com/ptaoussanis/sente/blob/548af55c5eb13a53e451b5214f58ecd45f20b0a5/example-project/src/example/client.cljs#L33)).

In most cases the change will involve three steps:

1. You need to include the server's CSRF token somewhere in your page HTML: [example](https://github.com/ptaoussanis/sente/blob/548af55c5eb13a53e451b5214f58ecd45f20b0a5/example-project/src/example/server.clj#L69).
2. You need to extract the CSRF token from your page HTML: [example](https://github.com/ptaoussanis/sente/blob/548af55c5eb13a53e451b5214f58ecd45f20b0a5/example-project/src/example/client.cljs#L33).
3. You'll then use the extracted CSRF token as an argument when calling `make-channel-socket-client!`: [example](https://github.com/ptaoussanis/sente/blob/548af55c5eb13a53e451b5214f58ecd45f20b0a5/example-project/src/example/client.cljs#L52).

#### Client-side `:chsk/handshake` event has changed

It now always has `nil` where it once provided the csrf-token provided by the server.

```
   I.e. before: [:chsk/handshake [<?uid> <csrf-token> <?handshake-data> <first-handshake?>]]
         after: [:chsk/handshake [<?uid> nil          <?handshake-data> <first-handshake?>]]
```

Most users won't be affected by this change.


## v1.13.1 - 2018 Aug 22

```clojure
[com.taoensso/sente "1.13.1"]
```

> This is a hotfix release, should be non-breaking

* [#327 #326] Fix broken ws->ajax downgrade logic (@michaelcameron)


## v1.13.0 - 2018 Aug 4

```clojure
[com.taoensso/sente "1.13.0"]
```

> This is a maintenance release, should be non-breaking in most cases

* Updated all dependencies


## v1.12.1 - 2018 Aug 4

```clojure
[com.taoensso/sente "1.12.1"]
```

> This is a non-breaking maintenance release

* [#323] **Fix**: Work correctly with new versions of `ring-anti-forgery` (@timothypratley)


## v1.12.0 - 2017 Dec 10

```clojure
[com.taoensso/sente "1.12.0"]
```

> This is a non-breaking bugfix release which updates some dependencies

* [#315 #314 #311] **Fix**: Updated dependencies (@theasp)


## v1.11.0 - 2016 Oct 13

```clojure
[com.taoensso/sente "1.11.0"]
```

> This is a non-breaking feature release

* [#255] **New**: Client chsk state: now include cause of chsk disconnections
* [#263] **New**: Client chsk state: add :udt-next-reconnect key (@danielcompton)
* [#259] **New**: Allow clients to detect sudden abnormal disconnects (e.g. airplane mode)
* [#265] **New**: Add :simple-auto-threading? option to routers
* [#257] **New**: Add disconnect and reconnect buttons to ref example (@danielcompton)
* [#270] **Impl**: Better Ajax broadcast reliability on very poor connections
* [#254] **Fix**: Make sure pending retries are subject to normal cancellation

## v1.10.0 - 2016 Jul 24

```clojure
[com.taoensso/sente "1.10.0"]
```

> This is a minor, non-breaking release focused on moving from .cljx -> .cljc

* [#242] **Impl**: Switch from .cljx to .cljc (@danielcompton)
* [#243] **Impl**: Add support for nodejs clients (@DaveWM)
* [#246] **New**: Add :protocol parameter to make-channel-socket-client! (@tiensonqin)
* [#247] **New**: `SENTE_ELIDE_JS_REQUIRE` environment var for use with **React Native**

## v1.9.0 - 2016 Jul 6

```clojure
[com.taoensso/sente "1.9.0"]
```

> This is a **particularly substantial release** focused on design refactoring, and a number of new features.

* **BREAKING**: Client-side event changed: `[:chsk/state <new-state-map>]` -> `[:chsk/state [<old-state-map> <new-state-map>]]`
* **BREAKING**: `:ws-kalive-ms`, `:lp-timeout-ms` opts moved from client-side to server-side `make-channel-socket!` fn
* **BREAKING**: Drop experimental (and rarely used) flexi packer
* **New**: Add Aleph server adapter (@sorenmacbeth) [#236]
* **New**: Client-side `:chsk/state` events may now contain `:last-ws-error`, `:last-ws-close` keys [#214]
* **New**: Add support for more flexible conn-type upgrade/downgrade [#201]
* **New**: Add new goodies to reference example
* **Impl**: Allow server to garbage collect long-polling conns [#150 #159]
* **Impl**: Server-side ping to help gc non-terminating WebSocket conns [#230]
* **Impl**: Servers now drive WebSocket identification (more robust, flexible)
* **Impl**: Clojure-side Transit performance optimizations [#161]
* **Fix**: Fixed faulty Nodejs Ajax adapter behaviour
* **Fix**: Fix for spurious Firefox unload->onclose calls [#224]
* **Fix**: Clear the keep alive timer in `chsk-disconnect!` [#221 @theasp]

## v1.8.1 - 2016 Mar 4

```clojure
[com.taoensso/sente "1.8.1"]
```

* **Hotfix**: add missing `event?` alias

## v1.8.0 - 2016 Feb 16

> This is a major **non-breaking** feature release, enjoy! :-)

* **Change**: `chsk-reconnect!` calls now always attempt reconnection immediately [#167]
* **Change**: Ref example has been refactored, simplified
* **Change**: Ref example has been split into client+server namespaces [#192 @theasp]
* **New**: Added server adapters for Node.js (generic, Express, Dog Fort) [#194 @theasp @whamtet]
* **New**: Added official `ajax-lite` alias (Sente Ajax req util)
* **New**: Added "carpet" example [#187 @ebellani]
* **New**: CSRF token header is now compatible with ring.middleware defaults [#198 @theasp]
* **Impl.**: Decoupled notion that clj<=>server, cljs<=>client [thanks to @theasp for assistance]
* **Impl.**: Refactor web-server adapter interfaces

```clojure
[com.taoensso/sente "1.8.0"]
```

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
