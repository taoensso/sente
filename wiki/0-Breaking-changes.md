This page details possible **breaking changes and migration instructions** for Sente.

My apologies for the trouble. I'm very mindful of the costs involved in breaking changes, and I try hard to avoid them whenever possible. When there is a very good reason to break, I'll try to batch breaks and to make migration as easy as possible.

Thanks for your understanding - [Peter Taoussanis](https://www.taoensso.com)

# Sente `v1.22` to `v1.23`

This upgrade involves **3 possible breaking changes** detailed below.

All 3 are relevant **only** for folks using the optional (experimental) [MessagePack or gzip packers](./5-Packers). If you're using the default edn packer or the Transit packer without gzip, no changes are needed.

**Change 1/3**

The MessagePack and gzip packers now enforce **default limits when unpacking**:

| Packer      | Limit                          | Default       | Option                    |
| ----------- | ------------------------------ | ------------- | ------------------------- |
| MessagePack | Size of any single value       | 64 MiB        | `:max-value-bytes`        |
| MessagePack | Items in any collection        | 1,048,576     | `:max-collection-items`   |
| MessagePack | Nesting depth                  | 512 levels    | `:max-nesting-depth`      |
| gzip        | Uncompressed size              | 64 MiB        | `:max-uncompressed-bytes` |

- **Old** behaviour: no limits
- **New** behaviour: unpacking a payload that exceeds a limit throws

The limits are generous and should rarely be hit in practice. If you do need larger payloads, raise only the relevant limit (or set it to `nil` to disable it) on whichever side does the unpacking:

```clojure
(def my-packer
  (taoensso.sente.packers.gzip/wrap-packer
    (taoensso.sente.packers.msgpack/get-packer {:max-value-bytes (* 256 1024 1024)})
    {:binary? true, :max-uncompressed-bytes (* 256 1024 1024)}))
```

**Motivation for change**: packers decode untrusted network input, and without limits a small malicious payload could exhaust memory or overflow the stack.

---

**Change 2/3**

The MessagePack packer now encodes numeric typed arrays as **big-endian** on all platforms.

This change is only relevant for folks sending `Int32Array`, `Float32Array`, or `Float64Array` values with a **Cljs** MessagePack packer. Byte arrays and all other types are unaffected.

- **Old** behaviour: Cljs used the platform's native byte order (usually little-endian), which didn't match the JVM
- **New** behaviour: Cljs uses big-endian, matching the JVM

If you're affected: pre-v1.23 and v1.23+ Cljs peers will silently misread each other's typed arrays, so please update all Cljs clients (and any Cljs servers) together. JVM servers were already big-endian. Any stored payloads containing these arrays that were written by a pre-v1.23 Cljs packer should be unpacked with the old version and re-packed with the new.

**Motivation for change**: typed arrays previously couldn't be exchanged correctly between Cljs and the JVM.

---

**Change 3/3**

The MessagePack packer now **throws instead of silently losing numeric precision**:

- **Cljs**: unpacking an integer outside JavaScript's safe range (±2^53-1) now throws. Previously the value was silently rounded.
- **Clj**: packing a `BigDecimal` that would lose precision as a double now throws. Previously the value was silently approximated.

If you're affected: send such values as strings (or via a custom extension), or explicitly coerce to a double before sending when precision loss is acceptable.

**Motivation for change**: silent data corruption is worse than a clear error.

---

**Heads-up for v1.24**

Sente v1.23 adds an opt-in way to keep WebSocket CSRF tokens out of request URLs, and v1.24 plans to make it the default. See [here](./6-CSRF-and-origin-protection#websocket-token-transport-sente-v123) for how to prepare.

# Sente `v1.17` to `v1.18`

This upgrade involves **4 possible breaking changes** detailed below:

**Change 1/4**

The default `wrap-recv-evs?` option has changed in [`make-channel-socket-client!`](http://taoensso.github.io/sente/taoensso.sente.html#var-make-channel-socket-client.21).

- **Old** default behaviour: events from server are **wrapped** with `[:chsk/recv <event>]`
- **New** default behaviour: events from server are **unwrapped**

**Motivation for change**: there's no benefit to wrapping events from the server, and this wrapping often causes confusion.

More info at: [#319](../issues/319)

---

**Change 2/4**

The default [`*write-legacy-pack-format?*`](http://taoensso.github.io/sente/taoensso.sente.html#var-*write-legacy-pack-format.3F*) value has changed from `true` to `false`.

This change is only relevant for the small minority of folks that use a custom (non-standard) [`IPacker`](https://github.com/taoensso/sente/blob/f69a5df6d1f3e88d66a148c74e1b5a9084c9c0b9/src/taoensso/sente/interfaces.cljc#L55).

If you do use a custom (non-standard) `IPacker`, please see the [relevant docstring](http://taoensso.github.io/sente/taoensso.sente.html#var-*write-legacy-pack-format.3F*) for details.

**Motivation for change**: the new default value is part of a phased transition to a new Sente message format that better supports non-string (e.g. binary) payloads.

More info at: [#398](../issues/398), [#404](../issues/404)

---

**Change 3/4**

Unofficial adapters have been moved to `community` dir.

This change is only relevant for folks using a server other than http-kit.

If you're using a different server, the adapter's namespace will now include a `.community` part, e.g.:

- **Old** adapter namespace: `taoensso.sente.server-adapters.undertow`
- **New** adapter namespace: `taoensso.sente.server-adapters.community.undertow`

**Motivation for change**: the new namespace structure is intended to more clearly indicate which adapters are/not officially maintained as part of the core project.

More info at: [#412](../issues/412)

---

**Change 4/4**

The `jetty9-ring-adapter` has been removed.

This change is only relevant for folks using `jetty9-ring-adapter`.

**Motivation for change**: it looks like the previous adapter may have been broken for some time. And despite [some effort](../issues/426) from the community, a new/fixed adapter isn't currently available. Further investigation is necessary, but it looks like it's _possible_ that the current `jetty9-ring-adapter` API might not support the kind of functionality that Sente needs for its Ajax fallback behaviour.

Apologies for this!

More info at: [#424](../issues/424), [#426](../issues/426)

---