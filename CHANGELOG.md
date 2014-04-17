## Pending / unreleased

 * **CHANGE**: `[:chsk/uidport-close :ajax]` event is now fired more intelligently (allows sime time for possible poller reconnection).


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
