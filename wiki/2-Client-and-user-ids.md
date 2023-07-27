Sente uses two types of connection identifiers: **client-ids** and **user-ids**.

# Client ids

A client-id is a unique identifier for one particular Sente client: i.e. one particular invocation of `make-channel-socket-client!`. This typically means **one particular browser tab** on one device.

By default, clients generate their own random (uuid) client-id. You can override this in your call to [`make-channel-socket-client!`](http://ptaoussanis.github.io/sente/taoensso.sente.html#var-make-channel-socket-client.21).

Note:
1. Each client chooses its _own_ client-id with no input from the server.
2. By default, each browser tab has its _own_ client-id.
3. By default, reloading a browser tab (or closing a tab + opening a new one) means a _new_ client-id.

# User ids

This is the more important concept in Sente, and is actually the only type of identifier supported by Sente's server>client push API.

A user-id is a unique application-level identifier associated with >=0 Sente clients (client-ids).

It is determined _server-side_ as a configurable function of each connecting channel socket's Ring request, i.e. `(fn user-id [ring-req]) => ?user-id`.

Typically, you'll configure Sente's user-id to be something like your application's username: if Alice logs into your application with 6 different browser tabs over 3 different devices - she'll have 6 client-ids associated with her user-id. And when your server sends an event "to Alice", it'll be delivered to all 6 of her connected clients.

By default, Sente will use `(fn user-id [ring-req] (get-in ring-req [:session :uid]))` as your user-id function. You can override this in your call to [`make-channel-socket-server!`](http://ptaoussanis.github.io/sente/taoensso.sente.html#var-make-channel-socket-server.21).

Note:

1. One user-id may be associated with 0, 1, or _many_ clients (client-ids).
2. By default (i.e. with the sessionized `:uid` value), user-ids are persistent and shared among multiple tabs in one browser as a result of the way browser sessions work.

# Examples

## Per-session persistent user-id

This is probably a good default choice.

1. `:client-id`: use default (random uuid)
2. `:user-id-fn`: use default, ensure that you sessionize a sensible `:uid` value on user login

## Per-tab transient user-id

I.e. each tab has its own user-id, and reloading a tab means a new user-id.

1. `:client-id`: use default (random uuid)
2. `:user-id-fn`: use `(fn [ring-req] (:client-id ring-req))`

I.e. we don't use sessions for anything. User-ids are equal to client-ids, which are random per-tab uuids.

## Per-tab transient user-id with session security

I.e. as above, but users must be signed in with a session.

1. `:client-id`: leave unchanged.
2. `:user-id-fn`: `(fn [ring-req] (str (get-in ring-req [:session :base-user-id]) "/" (:client-id ring-req)))`

I.e. sessions (+ some kind of login procedure) are used to determine a `:base-user-id`. That base user-id is then joined with each unique client-id. Each tab therefore retains its own user-id, but each user-id is dependent on a secure login procedure.