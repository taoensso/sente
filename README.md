**[API docs][]** | **[CHANGELOG][]** | [other Clojure libs][] | [Twitter][] | [contact/contributing](#contact--contributing) | current ([semantic][]) version:

```clojure
[com.taoensso/sente "8.0.0-SNAPSHOT"] ; Experimental
```

# Sente, channel sockets for Clojure

> **Sen-te** (先手) is a Japanese [Go][] term used to describe a play with such an overwhelming follow-up that it forces an immediate response, thus leaving its player with the initiative.

**Sente** is small client+server library for **robust, high-performance Clojure realtime web applications**.

##### Or: The missing piece in Clojure's web application story
##### Or: We don't need no Socket.IO
##### Or: Clojure(Script) + core.async + WebSockets/Ajax = _The Shiz_

## What's in the box™?
  * **Fully bidirectional a/sync comms** over both **WebSockets** and **Ajax**.
  * **Robust**: auto keep-alives, buffering, mode fallback, reconnects. **It just works™**.
  * Lean, flexible **[edn][]-based messaging protocol**. No json here.
  * **Trivial client+server side APIs**: a constructor and a single send fn.
  * Automatic, sensible support for users connected with **multiple clients** and/or devices simultaneously.
  * **Fully documented, with examples**.
  * **Less than 500 lines of code** for the entire client+server implementation.
  * **Flexible model**: works great with all kinds of app architectures + easy to migrate to for existing codebases.
  * **Supported servers**: currently **only [http-kit][]**. [PRs welcome](https://github.com/ptaoussanis/sente/issues/2) to add support for additional servers!


### Capabilities

Underlying protocol | client>server | client>server + ack/reply | server>clientS push |
------------------- | ------------- | ------------------------- | ------------------- |
WebSockets          | ✓ (native)    | ✓ (emulated)              | ✓ (native)          |
Ajax                | ✓ (emulated)  | ✓ (native)                | ✓ (emulated)        |

So the underlying protocol becomes irrelevant. Library consumers face a unified API that exposes the best of both WebSockets (bidirectionality + performance) and Ajax (optional evented ack/reply model).


## Getting started

Add the necessary dependency to your [Leiningen][] `project.clj`. This'll provide your project with both the client (ClojureScript) + server (Clojure) side library code:

```clojure
[com.taoensso/sente "8.0.0-SNAPSHOT"]
```

### On the server (Clojure) side

TODO

### On the client (ClojureScript) side

TODO


## This project supports the CDS and ![ClojureWerkz](https://raw.github.com/clojurewerkz/clojurewerkz.org/master/assets/images/logos/clojurewerkz_long_h_50.png) goals

  * [CDS][], the **Clojure Documentation Site**, is a **contributer-friendly** community project aimed at producing top-notch, **beginner-friendly** Clojure tutorials and documentation. Awesome resource.

  * [ClojureWerkz][] is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing. They've got a ton of great stuff, check 'em out!

## Contact & contributing

Please use the project's GitHub [issues page][] for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com][] or on [Twitter][]. Cheers!

## License

Copyright &copy; 2012-2014 Peter Taoussanis. Distributed under the [Eclipse Public License][], the same as Clojure.


[API docs]: <http://ptaoussanis.github.io/sente/>
[CHANGELOG]: <https://github.com/ptaoussanis/sente/blob/master/CHANGELOG.md>
[other Clojure libs]: <https://www.taoensso.com/clojure-libraries>
[Twitter]: <https://twitter.com/ptaoussanis>
[semantic]: <http://semver.org/>
[Leiningen]: <http://leiningen.org/>
[CDS]: <http://clojure-doc.org/>
[ClojureWerkz]: <http://clojurewerkz.org/>
[issues page]: <https://github.com/ptaoussanis/sente/issues>
[commit history]: <https://github.com/ptaoussanis/sente/commits/master>
[taoensso.com]: <https://www.taoensso.com>
[Eclipse Public License]: <https://raw2.github.com/ptaoussanis/sente/master/LICENSE>
[Go]: <http://en.wikipedia.org/wiki/Go_game>
[edn]: <https://github.com/edn-format/edn>
[http-kit]: <https://github.com/http-kit/http-kit>