This project uses [**Break Versioning**](https://www.taoensso.com/break-versioning).

---

# `v1.20.0` (2024-12-31)

- **Dependency**: [on Clojars](https://clojars.org/com.taoensso/sente/versions/1.20.0)
- **Versioning**: [Break Versioning](https://www.taoensso.com/break-versioning)

This is a major **non-breaking maintenance and feature release**. As always, **please report any unexpected problems** 🙏 - [Peter Taoussanis](https://www.taoensso.com)

Happy holidays everyone! 🎄🫶

## Since `v1.20.0-RC1` (2024-10-28)

> No breaking changes intended

* **\[fix]** [#458] Fix React Native build: catch invalid call \[4e3f16c]
* **\[new]** [#447] [Community adapters] Support both Jetty 11 and 12 (@stefanroex) \[79c784d]
* **\[new]** [#447] [Community adapters] Improve error message on Ajax read timeouts \[9da662c]
* **\[doc]** [Community adapters] Improve constructor docstrings \[1c7a93c]

## Since `v1.19.2` (2023-08-30)

> No breaking changes intended

### Changes

* **\[mod]** [#440] Decrease log level of noisy ws-ping events (@jwr) \[4241e6c]
* **\[mod]** Tune send backoff time \[84e8b2a]

### Fixes

* **\[fix]** [#448] [#453] Fix NodeJS build: don't add `beforeunload` event listener (@theasp) \[dc6b34e]
* **\[fix]** [#458] Fix React Native build: catch invalid call \[4e3f16c]
* **\[fix]** [#445] [#444] [Community adapters] Undertow: remove invalid option (@danielsz) \[55167f5]

### New

* **\[new]** [#447] [Community adapters] Add Jetty 11/12 adapter (@alexandergunnarson) \[8ecb2d9]
* **\[doc]** [Community adapters] Improve constructor docstrings \[1c7a93c]
* **\[doc]** [#439] Add guidance on large transfers \[513a42d]

---

# `v1.20.0-RC1` (2024-10-28)

- **Dependency**: [on Clojars](https://clojars.org/com.taoensso/sente/versions/1.20.0-RC1)
- **Versioning**: [Break Versioning](https://www.taoensso.com/break-versioning)

This is a major **non-breaking maintenance and feature release**. As always, **please report any unexpected problems** 🙏 - [Peter Taoussanis](https://www.taoensso.com)

## Changes since `v1.19.2` (2023-08-30)

* \[mod] [#440] Decrease log level of noisy ws-ping events (@jwr) [4241e6c]
* \[mod] Tune send backoff time [84e8b2a]

## Fixes since `v1.19.2` (2023-08-30)

* \[fix] [#448] [#453] Don't add `beforeunload` event listener when running inside NodeJS (@theasp) [dc6b34e]
* \[fix] [#445] [#444] [Community adapters] Undertow: remove invalid option (@danielsz) [55167f5]

## New since `v1.19.2` (2023-08-30)

* \[new] [#447] [Community adapters] Add Jetty 11 adapter (@alexandergunnarson) [8ecb2d9]
* \[doc] [#439] Add guidance on large transfers [513a42d]
* Update several dependencies

---

# `v1.19.2` (2023-08-30)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.19.2)

Identical to `v1.19.1`, but includes a hotfix (dbb798a) for [#434] to remove the unnecessary logging of potentially sensitive Ring request info when connecting to a server without a client id.

This should be a safe update for users of `v1.19.x`.

---

# `v1.19.1` (2023-07-18)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.19.1)

Identical to `v1.19.0`, but synchronizes Encore dependency with my recent library releases (Timbre, Tufte, Sente, Carmine, etc.) to prevent confusion caused by dependency conflicts.

This is a safe update for users of `v1.19.0`.

---

# `v1.19.0` (2023-07-13)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.19.0)

This is intended as a **non-breaking maintenance release**, but it touches a lot of code so **please keep an eye out** for (and let me know about) any unexpected problems - thank you! 🙏

**Tip**: the [reference example](https://github.com/taoensso/sente/tree/master/example-project) includes a number of tools to help test Sente in your environment.

## Fixes since `v1.18.1`

* 0dc8a12 [fix] [#431] Some disconnected user-ids not removed from `connected-uids`

## New since `v1.18.1`

* e330ef2 [new] Allow WebSocket constructors to delay connection
* 6021258 [new] [example] Misc improvements to example project
* d0fd918 [new] Alias client option: `:ws-kalive-ping-timeout-ms` -> `:ws-ping-timeout-ms`
* GraalVM compatibility is now tested during build

---

# `1.18.1` (2023-07-04)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.18.1)

This is an important **hotfix release**, please update if you're using `v1.18.0`.

## Fixes since `v1.18.0`

* ad62f1e [fix] Ajax poll not properly timing out
* 1d15fe5 [fix] [#430] `[:chsk/uidport-close]` server event not firing

## New since `v1.18.0`

* 5c0f4ad [new] [example] Add example server-side uidport event handlers

---

# `v1.18.0` (2023-06-30)

> 📦 [Available on Clojars](https://clojars.org/com.taoensso/sente/versions/1.18.0)

Same as `v1.18.0-RC1`, except for:

* 7889a0b [fix] [#429] Bump deps, fix possible broken cljs builds

---

# Earlier releases

See [here](https://github.com/taoensso/sente/releases) for earlier releases.
