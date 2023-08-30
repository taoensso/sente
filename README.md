<a href="https://www.taoensso.com/clojure" title="More stuff by @ptaoussanis at www.taoensso.com"><img src="https://www.taoensso.com/open-source.png" alt="Taoensso open source" width="340"/></a>  
[**Documentation**](#documentation) | [Latest releases](#latest-releases) | [Get support][GitHub issues]

# Sente

### Realtime web comms for Clojure/Script applications

**Sente** is a small client+server library that makes it easy to build **realtime web applications** with Clojure + ClojureScript.

Loosely inspired by [Socket.IO](https://socket.io/), it uses **core.async**, **WebSockets**, and **Ajax** under the hood to provide a simple high-level API that enables **reliable, high-performance, bidirectional communications**.

<img width="600" src="../../raw/master/hero.jpg"/>

> **Sen-te** (先手) is a Japanese [Go](https://en.wikipedia.org/wiki/Go_(game)) term used to describe a play with such an overwhelming follow-up that it demands an immediate response, leaving its player with the initiative.

## Latest release/s

- `2023-08-30` `1.19.2`: [changes](../../releases/tag/v1.19.2)

[![Main tests][Main tests SVG]][Main tests URL]
[![Graal tests][Graal tests SVG]][Graal tests URL]

See [here][GitHub releases] for earlier releases.

## Why Sente?

- **Bidirectional a/sync comms** over **WebSockets** with **auto Ajax fallback**
- **It just works**: auto keep-alive, buffering, protocol selection, reconnects
- **Efficient design** with auto event batching for low-bandwidth use, even over Ajax
- Send **arbitrary Clojure vals** over [edn](https://github.com/edn-format/edn
) or [Transit](https://github.com/cognitect/transit-clj) (JSON, MessagePack, etc.)
- Tiny, easy-to-use [API](../../wiki/1-Getting-started#usage)
- Support for users simultaneously connected with **multiple clients** and/or devices
- Realtime info on **which users are connected**, and over which protocols
- Standard **Ring security model**: auth as you like, HTTPS when available, CSRF support, etc.
- Support for [several popular web servers](../../tree/master/src/taoensso/sente/server_adapters), [easily extended](../../blob/master/src/taoensso/sente/interfaces.cljc) to other servers.

### Capabilities

Protocol   | client>server  | client>server + ack/reply | server>user push
---------- | -------------- | ------------------------- | ----------------
WebSockets | ✓ (native)    | ✓ (emulated)              | ✓ (native)
Ajax       | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)

So you can ignore the underlying protocol and deal directly with Sente's unified API that exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional ack/reply).

## Documentation

- [Full documentation][GitHub wiki] (**getting started** and more)
- Auto-generated API reference: [Codox][Codox docs], [clj-doc][clj-doc docs]

## Funding

You can [help support][sponsor] continued work on this project, thank you!! 🙏

## License

Copyright &copy; 2012-2023 [Peter Taoussanis][].  
Licensed under [EPL 1.0](LICENSE.txt) (same as Clojure).

<!-- Common -->

[GitHub releases]: ../../releases
[GitHub issues]:   ../../issues
[GitHub wiki]:     ../../wiki

[Peter Taoussanis]: https://www.taoensso.com
[sponsor]:          https://www.taoensso.com/sponsor

<!-- Project -->

[Codox docs]:   https://taoensso.github.io/sente/
[clj-doc docs]: https://cljdoc.org/d/com.taoensso/sente/

[Clojars SVG]: https://img.shields.io/clojars/v/com.taoensso/sente.svg
[Clojars URL]: https://clojars.org/com.taoensso/sente

[Main tests SVG]:  https://github.com/taoensso/sente/actions/workflows/main-tests.yml/badge.svg
[Main tests URL]:  https://github.com/taoensso/sente/actions/workflows/main-tests.yml
[Graal tests SVG]: https://github.com/taoensso/sente/actions/workflows/graal-tests.yml/badge.svg
[Graal tests URL]: https://github.com/taoensso/sente/actions/workflows/graal-tests.yml
