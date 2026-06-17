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

Sente compares a session reference token against the client's (the `:csrf-token` param, or `x-csrf-token` / `x-xsrf-token` header). Add anti-forgery middleware ([ring-anti-forgery](https://github.com/ring-clojure/ring-anti-forgery) or [ring-defaults](https://github.com/ring-clojure/ring-defaults)) and pass the token to your client; see [Getting started](./1-Getting-started).

Disable it by returning `:sente/skip-CSRF-check` from `:csrf-token-fn`.

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

Read the token from anywhere, e.g. the `Sec-WebSocket-Protocol` header (WebSockets can't set custom headers). Compare secret tokens in constant time ([`const-str=`](https://cljdoc.org/d/com.taoensso/encore/CURRENT/api/taoensso.encore#const-str=)).

> `:reject-fn` replaces the deprecated `:?unauthorized-fn`.
