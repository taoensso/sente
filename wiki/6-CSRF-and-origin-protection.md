Sente checks every connection request (Ajax POST, WebSocket/Ajax handshake) before accepting it. Configure the checks via [`make-channel-socket-server!`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente#make-channel-socket-server!) options.

> CSRF protection is **strongly recommended** for websites. The default token setup is in [Getting started](./1-Getting-started); this page covers the options.

# Rejection pipeline

Checks run in order; the first failure rejects the request:

| # | Check               | Option             | Default rejection      |
|---|---------------------|--------------------|------------------------|
| 1 | Custom (any reason) | `:reject-fn`       | your returned response |
| 2 | CSRF                | `:csrf-token-fn`   | `:bad-csrf-fn` → 403   |
| 3 | Origin              | `:allowed-origins` | `:bad-origin-fn` → 403 |
| 4 | Authorization       | `:authorized?-fn`  | `:unauthorized-fn` → 401 |

Each responder is a `(fn [ring-req]) -> ring-resp` you can override.

# Token CSRF (default)

Sente compares a session reference token against the client's (the `:csrf-token` param, `x-csrf-token` / `x-xsrf-token` header, or [WebSocket subprotocol](#websocket-token-transport-sente-v123)). Add anti-forgery middleware ([ring-anti-forgery](https://github.com/ring-clojure/ring-anti-forgery) or [ring-defaults](https://github.com/ring-clojure/ring-defaults)) and pass the token to your client; see [Getting started](./1-Getting-started).

Disable it by returning `:sente/skip-CSRF-check` from `:csrf-token-fn`.

## WebSocket token transport (Sente v1.23+)

Browsers can't set custom headers on WebSocket handshakes, so Sente's client has always sent the token for these as a `csrf-token` URL param. URLs commonly end up in server and proxy logs, which can leak the token.

Sente v1.23 adds a protected transport that sends the token in the `Sec-WebSocket-Protocol` header instead (or the `X-CSRF-Token` header for Java clients). It's currently **opt-in** via a client option:

```clojure
(sente/make-channel-socket-client! "/chsk" ?csrf-token
  {:legacy-ws-csrf-token-in-url? false ; Keep token out of URL
   ...})
```

To roll out safely:

1. **First** upgrade all servers to Sente v1.23+. They accept both transports, no config needed.
2. **Then** set `:legacy-ws-csrf-token-in-url? false` on your clients.

Browser clients using the protected transport can't open a WebSocket to a pre-v1.23 server. Note that `:type :auto` clients will then quietly fall back to Ajax, so when testing please check that you're actually getting a WebSocket connection.

Sente v1.24 plans to change the default to `false`, so it's worth testing the new transport now.

Things to check if you have a custom setup:

- **Custom server adapters** must select the `:ws-protocol` they're given in the WebSocket upgrade response, see [`IServerChanAdapter`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.interfaces#IServerChanAdapter). Sente's bundled JVM adapters do this already.
- **Custom `:ws-constructor` fns** must pass on the `:protocols` (browser) or `:headers` (Java) they're given.
- **Express adapter**: Sente's token check is delegated to `csurf` by default, which commonly ignores GET requests (incl. WebSocket handshakes). Please make sure to set `:allowed-origins` or have `csurf` check GETs.
- **Custom `:csrf-token-fn` / `:reject-fn`** logic using [`valid-csrf-token?`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente#valid-csrf-token?) needs no changes. Logic that reads the `csrf-token` param directly will need updating.

# Origin CSRF

A same-origin request can't be cross-site, so checking `Origin` (with a `Referer` fallback, per [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)) is a token-free CSRF defense. `:allowed-origins` is an always-on origin gate:

```clojure
{:allowed-origins #{"https://example.com"}
 :csrf-token-fn   (constantly :sente/skip-CSRF-check)} ; Origin is the CSRF defense
```

Bad origins reject via `:bad-origin-fn`.

# Custom: `:reject-fn` (Sente v1.22+)

One hook to reject a request for **any** reason: `(fn [ring-req]) -> ?resp`. Non-nil rejects with that response; nil falls through to the built-in checks. It runs first and can only *add* rejections.

Use it for CSRF the token check can't express (signed token, double-submit cookie, [Fetch Metadata](https://web.dev/articles/fetch-metadata)…) via the [`allow-origin?`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente#allow-origin?) and [`valid-csrf-token?`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente#valid-csrf-token?) helpers, or for reasons Sente doesn't model (rate limits, IP/maintenance gates):

```clojure
;; Accept a trusted origin OR a valid token (e.g. browser + API clients).
;; Disable the built-in token check, since we handle CSRF here:
{:csrf-token-fn (constantly :sente/skip-CSRF-check)
 :reject-fn
 (fn [req]
   (when-not (or (sente/allow-origin?     #{"https://example.com"} req)
                 (sente/valid-csrf-token? my-token-fn              req))
     {:status 403}))}

;; Rate-limit, block IPs, etc.:
{:reject-fn (fn [req] (when (rate-limited? req) {:status 429}))}
```

Read the client's token from anywhere it can be sent, e.g. a custom header or param. Compare secret tokens in constant time ([`const-str=`](https://cljdoc.org/d/com.taoensso/encore/CURRENT/api/taoensso.encore#const-str=)).

> `:reject-fn` replaces the deprecated `:?unauthorized-fn`.
