> This project uses [Break Versioning](https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md) as of **Aug 16, 2014**.

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

 * [#27] **FIX** broken :advanced mode compilation (@ostronom).


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
