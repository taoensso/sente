Some info is provided here on **protocol-level debugging and profiling** for Sente connections.

# Ajax connections

These are easily debugged and profiled via your browser's usual network tools.

# WebSocket connections

You can inspect Sente packets using `Wireshark` or similar tools.

Assuming Sente doesn't degrade to Ajax, the initial [WebSocket upgrade](https://tools.ietf.org/html/rfc6455#section-1.2) handshake will include a `:client-id` parameter:

```
GET /chsk?client-id=bd5ee0f2-dc22-47b5-98ab-618711f34b45 HTTP/1.1
Host: localhost:3000
Connection: Upgrade
Upgrade: websocket
Origin: http://localhost:3000
Sec-WebSocket-Version: 13
...
```

This is important if you want to emulate Sente's behavior using benchmarking tools like [tcpkali](https://github.com/machinezone/tcpkali), etc. Without this Sente will throw an exception and the benchmark will fail.

Afterwards, you'll see a series of TCP packets as per the Websocket protocol and containing the `[<ev-id> <?ev-data>]` vector encoded according to the selected Packer. For instance with Transit:

```
`e@7.KcJam
43C-["~:chsk/handshake",["bd5ee0f2-dc22-47b5-98ab-618711f34b45",null]]
```

## What is the `+` character I see attached to my Websocket `?ev-data`?

This is a normal part of Sente's payload encoding.

See [here](https://github.com/arichiardi/sente/blob/162149663e63fcda0348fb8d28d5533c4d0004cd/src/taoensso/sente.cljc#L212) for the gory details if you need to reproduce this behaviour.