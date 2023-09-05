This page details possible **breaking changes and migration instructions** for Sente.

My apologies for the trouble. I'm very mindful of the costs involved in breaking changes, and I try hard to avoid them whenever possible. When there is a very good reason to break, I'll try to batch breaks and to make migration as easy as possible.

Thanks for your understanding - [Peter Taoussanis](https://www.taoensso.com)

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