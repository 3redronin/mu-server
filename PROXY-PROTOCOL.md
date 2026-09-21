# PROXY protocol listeners

Mu4 accepts HAProxy PROXY protocol v1 and v2 when explicitly enabled:

```java
MuServer server = MuServerBuilder.httpsServer()
    .withHAProxyProtocolEnabled(true)
    .withHAProxyProtocolTimeout(10, TimeUnit.SECONDS)
    .addHandler((request, response) -> {
        ProxiedConnectionInfo info = request.connection().proxyInfo().orElseThrow();
        response.write(String.valueOf(info.sourceAddress()));
        return true;
    }).start();
```

Configure the listener's bind address, firewall, security group or network policy
so only trusted proxies can connect. Mu4 does not authenticate PROXY metadata or
provide a proxy allowlist. Every connection to an enabled listener must carry one
preamble, before TLS or HTTP. Health checks must send one too. Enable HAProxy's
`send-proxy` or `send-proxy-v2` on the backend server line. TLS passthrough leaves
the TLS handshake after that preamble. The feature is disabled by default.

`HttpConnection.proxyInfo()` describes the entire connection. All keep-alive
requests, pipelined requests, HTTP/2 streams and a WebSocket upgrade share this
metadata. Do not reuse one backend connection for unrelated client identities.
`remoteAddress()` and `localAddress()` continue to describe the actual socket.
Disabled connections have an empty Optional. UNKNOWN, LOCAL and UNSPEC headers
produce metadata with null addresses and zero ports. Zero is also a valid
advertised IP port, so a zero port alone does not imply absent metadata.

The overall timeout defaults to ten seconds from acceptance, including executor
queue time. HTTP idle and request timeouts do not change it. Positive durations
must represent at least one millisecond and fit in a signed nanosecond duration;
fractional milliseconds are truncated. Invalid or incomplete headers close the
connection without dispatching an HTTP handler. Internal executor rejection
closes a pending connection immediately and records overload. Pending reads are
closed on shutdown; socket read timeouts are restored before TLS/HTTP processing.

V1 is limited to 107 bytes including CRLF. Numeric IPv4 and IPv6 literals are
validated without DNS. Decimal addresses and ports reject leading zeroes; ports
range from 0 through 65535. UNKNOWN ignores its suffix. V2 supports IPv4, IPv6
and UNIX address blocks, with stream and datagram descriptions (the Mu listener
itself still uses TCP). IPv6 output retains eight hexadecimal groups, including
IPv4-mapped addresses. UNIX addresses end at the first zero or 108 bytes.

V2 consumes only the declared payload and uses a 4 KiB discard buffer. LOCAL
ignores the advertised family and payload. Unknown TLVs are discarded after
outer framing validation; nested SSL assertions are not interpreted and CRC32C
is not verified. Proxy SSL assertions cannot supply a client certificate or
change the actual TLS status.

Compatibility is assessed against the checked-out Mu3 implementation and the
[HAProxy specification](https://www.haproxy.org/download/3.0/doc/proxy-protocol.txt).
Mu4 intentionally accepts port zero, rejects noncanonical decimal leading
zeroes and oversized v1 lines, and validates outer TLV framing. It adds an
acceptance-based preamble timeout. These are protocol/lifecycle requirements;
Mu3 accepting an input does not establish that the input is valid. Independent
release evidence, raw observations and compatibility assessments belong in the
local `mu-conformance` repository, not in the server distribution.
