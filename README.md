<a href="https://www.taoensso.com/clojure" title="More stuff by @ptaoussanis at www.taoensso.com"><img src="https://www.taoensso.com/open-source.png" alt="Taoensso open source" width="340"/></a>  
[**API**][cljdoc] | [**Wiki**][GitHub wiki] | [Latest releases](#latest-releases) | [Slack channel][]

# Sente

### Realtime web comms library for Clojure/Script

**Sente** is a small client+server library that makes it easy to build **realtime web applications** with Clojure + ClojureScript.

Loosely inspired by [Socket.IO](https://socket.io/), it uses **core.async**, **WebSockets**, and **Ajax** under the hood to provide a simple high-level API that enables **reliable, high-performance, bidirectional communications**.

<img width="600" src="../../raw/master/hero.jpg"/>

> **Sen-te** (先手) is a Japanese [Go](https://en.wikipedia.org/wiki/Go_(game)) term used to describe a play with such an overwhelming follow-up that it demands an immediate response, leaving its player with the initiative.

## Latest release/s

- `2025-09-02` `v1.21.0-RC1` (dev): [release info](../../releases/tag/v1.21.0-RC1)
- `2024-12-31` `v1.20.0` (stable): [release info](../../releases/tag/v1.20.0)

[![Clj tests][Clj tests SVG]][Clj tests URL]
[![Cljs tests][Cljs tests SVG]][Cljs tests URL]
[![Graal tests][Graal tests SVG]][Graal tests URL]

See [here][GitHub releases] for earlier releases.

## Why Sente?

- **Bidirectional a/sync comms** over **WebSockets** with **auto Ajax fallback**
- **It just works**: auto keep-alive, buffering, protocol selection, reconnects
- **Efficient design** with auto event batching for low-bandwidth use, even over Ajax
- Send **arbitrary Clojure vals** with **high-speed binary serialization** (v1.21+) or [edn](https://github.com/edn-format/edn)
- Tiny, easy-to-use [API](../../wiki/1-Getting-started#usage)
- Support for users simultaneously connected with **multiple clients** and/or devices
- Realtime info on **which users are connected**, and over which protocols
- Standard **Ring security model**: auth as you like, HTTPS when available, CSRF support, etc.
- Support for [several popular web servers](../../tree/master/src/taoensso/sente/server_adapters), [easily extended](../../blob/master/src/taoensso/sente/interfaces.cljc) to other servers.

### Capabilities

| Protocol   | client>server | client>server + ack/reply | server>user push |
| ---------- | ------------- | ------------------------- | ---------------- |
| WebSockets | ✓ (native)    | ✓ (emulated)              | ✓ (native)       |
| Ajax       | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)     |

So you can ignore the underlying protocol and deal directly with Sente's unified API that exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional ack/reply).

## Documentation

- [Wiki][GitHub wiki] (getting started, usage, etc.)
- API reference via [cljdoc][cljdoc]
- Support via [Slack channel][] or [GitHub issues][]

## Funding

You can [help support][sponsor] continued work on this project and [others][my work], thank you!! 🙏

## License

Copyright &copy; 2012-2025 [Peter Taoussanis][].  
Licensed under [EPL 1.0](LICENSE.txt) (same as Clojure).

<!-- Common -->

[GitHub releases]: ../../releases
[GitHub issues]:   ../../issues
[GitHub wiki]:     ../../wiki
[Slack channel]: https://www.taoensso.com/sente/slack

[Peter Taoussanis]: https://www.taoensso.com
[sponsor]:          https://www.taoensso.com/sponsor

<!-- Project -->

[cljdoc]: https://cljdoc.org/d/com.taoensso/sente/CURRENT/api/taoensso.sente

[Clojars SVG]: https://img.shields.io/clojars/v/com.taoensso/sente.svg
[Clojars URL]: https://clojars.org/com.taoensso/sente

[Clj tests SVG]:  https://github.com/taoensso/sente/actions/workflows/clj-tests.yml/badge.svg
[Clj tests URL]:  https://github.com/taoensso/sente/actions/workflows/clj-tests.yml
[Cljs tests SVG]:  https://github.com/taoensso/sente/actions/workflows/cljs-tests.yml/badge.svg
[Cljs tests URL]:  https://github.com/taoensso/sente/actions/workflows/cljs-tests.yml
[Graal tests SVG]: https://github.com/taoensso/sente/actions/workflows/graal-tests.yml/badge.svg
[Graal tests URL]: https://github.com/taoensso/sente/actions/workflows/graal-tests.yml
