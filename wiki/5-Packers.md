Sente uses a pluggable "**packer**" to control the format used for all client<->server communications.

The following packers are available out-the-box:

| Format                                                 | Packer                                                                                     | Type        | Comments                      |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------ | ----------- | ----------------------------- |
| [edn](https://github.com/edn-format/edn)               | [Link](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.packers.edn)     | Text        | Mature, default               |
| [Transit](https://github.com/cognitect/transit-format) | [Link](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.packers.transit) | Text (JSON) | Mature                        |
| [MessagePack](https://msgpack.org/index.html)          | [Link](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.packers.msgpack) | Binary      | New high-speed implementation |
| [gzip](https://en.wikipedia.org/wiki/Gzip)             | [Link](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.packers.gzip)    | Text+Binary | Wraps any other packer        |

Custom packers can also be easily written by implementing the [relevant protocol](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente.interfaces#IPacker2).

# Config

To use a packer you'll import it and provide it as an option to **both** your Sente server **and** client, e.g.:

```clojure
;; Server (clj)
(def my-sente-server
  (let [packer (taoensso.sente.packers.msgpack/get-packer)]
    (sente/make-channel-socket-server! ... {:packer packer ...})))

;; Client (cljs)
(def my-sente-client
  (let [packer (taoensso.sente.packers.msgpack/get-packer)]
    (sente/make-channel-socket-client! ... {:packer packer ...})))
```

To add gzip you'll wrap the underlying packer:

```clojure
;; Server (clj)
(def my-sente-server
  (let [mp-packer (taoensso.sente.packers.msgpack/get-packer)
        gz-packer (taoensso.sente.packers.gzip/wrap-packer mp-packer {:binary? true})]
    (sente/make-channel-socket-server! ... {:packer gz-packer ...})))

;; Client (cljs)
(def my-sente-client
  (let [mp-packer (taoensso.sente.packers.msgpack/get-packer)
        gz-packer (taoensso.sente.packers.gzip/wrap-packer mp-packer {:binary? true})]
    (sente/make-channel-socket-client! ... {:packer gz-packer ...})))
```

# MessagePack options (Sente v1.23+)

The MessagePack packer supports a few options via `(get-packer <opts>)`. The wire formats of the first two below are still **experimental**, feedback welcome!

## Shared dictionaries

Sente apps often send many small messages with the same keywords and strings. A shared dictionary lets you declare these up-front so that each occurrence is sent as a small reference:

```clojure
;; In shared cljc code
(def my-dict (taoensso.msgpack/shared-dict [:user/name :user/email "country" "currency"]))

;; On BOTH server and client
(taoensso.sente.packers.msgpack/get-packer {:shared-dict my-dict})
```

References cost 3-4 bytes, plus ~7 bytes once per message that uses the dictionary. So dictionaries help most with longer entries (e.g. namespaced keywords) and messages with several hits.

The dictionary is part of your wire format, so:

- A peer can only unpack what was packed with the **same dictionary**, or an older version of it. A mismatch throws rather than returning bad data.
- **Never remove or reorder** entries, only append.
- Upgrade **all** servers and clients to v1.23.0-RC2+ before using a dictionary or the rollout steps below. Older peers ignore `:pack-shared-dict`, and pre-v1.23 peers can't read dictionary references (they won't throw, they'll return bad data).
- Put your most common entries first (the first 256 get the smallest references).

To add or extend a dictionary without downtime, roll it out in two steps:

1. **First** give all servers and clients the new dictionary to unpack with, while they keep packing as before: `{:shared-dict new-dict, :pack-shared-dict old-dict-or-nil}`.
2. **Then** once every peer that can still connect has the new dictionary, remove the `:pack-shared-dict` option.

Otherwise you'll need to update server and clients together, and already-connected clients will fail until reloaded.

See the [`shared-dict`](https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.msgpack#shared-dict) docstring for details.

## Automatic keyword caching

With `{:auto-cache-keywords? true}`, keywords that repeat within a single message are automatically sent as small references, no dictionary needed. Repeated map keys are always cached, so this mostly helps with repeated keyword *values* (e.g. `:status :active`).

This is off by default since older peers can't read the new encoding (pre-v1.23 peers won't throw, they'll return bad data). To enable: first upgrade all servers and clients to v1.23.0-RC2+, then turn the option on.

## Unpack limits

MessagePack and gzip unpacking is limited by default to guard against malicious payloads. See [here](./0-Breaking-changes#sente-v122-to-v123) for the limits and how to change them.

# Which to use

A few things to consider include:

1. Packer **speed** and **output size**
2. Extra **dependencies**
3. **Browser support** and support for Clojure's **data types**

You can see the relevant packer docs for info re: (2) and (3).

Re: (1) very broadly:

- For **speed**: MessagePack ~ Transit >> edn
- For **small size**: MessagePack > Transit >> edn

Adding gzip will **decrease speed** in exchange for **smaller size** (often a good tradeoff).

There's some [benchmarks](https://github.com/taoensso/sente/blob/ea59599d6690d293a9caab2fb6bf728469158118/test/taoensso/sente_tests.cljc#L152) in the unit tests which you can tweak and run in your own environment. With the defaults (*smaller bars are better*):

<img alt="Benchmarks" src="https://github.com/user-attachments/assets/2b9c1bae-1b3a-4cd7-8d18-bea5dc6becda" />

Sente's [Reference example](https://github.com/taoensso/sente/tree/master/example-project) also allows you to easily switch between different packers for testing, etc.