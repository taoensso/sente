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