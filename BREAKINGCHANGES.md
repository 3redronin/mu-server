Version 4
=========

Required Java version
---------------------

Java 8 is no longer supported. Java 11 or later must be used.

Note it is highly recommended to use Java 21 or later to take advantage of virtual threads (earlier versions of Java
will result in a larger number of threads being used).

MuRequest and MuResponse API
----------------------------

* `MuResponse.status()` now returns a `HttpStatus` value rather than an `int`. For the int, call
  `MuResponse.status().code()`

Query string semicolons
-----------------------

Mu 4 only treats `&` as a query-parameter separator. Semicolons are data in parameter names and values, including when
query parameters are decoded with HTML form compatibility. For example, `?value=a;b` produces a single parameter named
`value` with the value `a;b`.

This differs from Mu 2's Netty query decoder, which treats both `&` and `;` as separators. Applications that used
semicolon-separated query parameters such as `?one=1;two=2` must change them to `?one=1&two=2` when upgrading to Mu 4.

HTTP/1 absolute request targets
------------------------------

For an absolute-form request such as `GET http://example.com/path HTTP/1.1`, the target's scheme and authority
now determine `MuRequest.uri()` before forwarding headers are applied. The application-visible `Host` is replaced
with the target authority, matching HTTP/2's use of `:authority`. Missing HTTP/1.1 Host, duplicate or malformed Host,
and malformed targets are rejected before replacement, even when forwarding headers are present.

Use `MuRequest.isSecure()` for request security: it considers forwarding protocol metadata, then connection security.
An `https` scheme in a plaintext absolute request target does not make the request secure. `serverURI()` continues
to describe the local listener. Forwarding header trust and precedence are unchanged.

Authorities support URI registered names (including underscores and percent escapes) and IPv6. IPvFuture literals
remain unsupported because `java.net.URI`, the request URI representation, cannot represent them.

Multipart form limits
---------------------

Mu's built-in `multipart/form-data` parser now accepts at most 1,024 parts per request by default. Each field or
file counts as a part, including repeated field names and parts ignored because they have no form field name.
Exceeding the limit returns HTTP 413 (Content Too Large), and any temporary uploads already created are deleted.
For REST resources, the default exception mapper returns a 413 problem-details response. Application-provided
exception mappers can customize the response.

Applications that need larger multipart forms can set `MuServerBuilder.withMaxMultipartParts(int)` when creating
the server. For example, `withMaxMultipartParts(4096)` allows up to 4,096 parts. Zero rejects any form containing
a part; negative values are not allowed. This limit is separate from the request body size limit and does not
apply to JSON or `application/x-www-form-urlencoded` bodies.

The default REST exception mapper now preserves the status and applicable response headers of `HttpException`,
instead of treating it as an unexpected 500 error. For 4xx/5xx statuses it generates a problem-details body;
headers describing an old representation are removed, and 5xx exception messages are not exposed to clients.

SSE
---

* `SsePublisher.start()` no longer puts the request in async mode. `AsyncSsePublisher.start()` can be used instead.
* `SsePublisher` now implements `Closeable` and so the `close()` method now throws an `IOException`.
* Disconnected clients are only detected when request read and writes occur (or if the server's connection idle timeout occurs). 
This is most likely to affect long-running HTTP1 connections, for example SSE connections that do not do much writing.
The best way to detect disconnected clients is to do frequent IO calls - e.g. for SSE connections write comments periodically.

Websockets
----------

Callbacks such as `onText` and `onBinary` no longer have `DoneCallback` callbacks to call. Instead, implementations should
block in those methods until the data is no longer needed.

Partial messages are now received separately, with `MuWebSocket.onPartialText` and `onPartialBinary` methods. Both of
these receive ByteBuffer objects with the partial message (even the text method, as the partial message may contain
invalid UTF-8 strings until aggregated).

If extending `BaseWebsocket` then aggregation of partial messages is performed by concatenating messages in memory
and calling the `onText(String)` or `onBinary(ByteBuffer)` methods.

The versions of the methods with the `DoneCallback` parameters have been removed from the `MuWebSocket` interface, however
`BaseWebsocket` has new implementations that call the non-blocking versions, using a future on the default forkjoin pool
to run.

The implication is that:

* If you extend from `BaseWebSocket` this should be a non-breaking change for receving messages, however you are encouraged to move to the
  blocking versions of the callbacks for a more efficient implementation. The new base class recommended to be overriden is `SimpleWebSocket`.
    * Note though that the callback versions of `onPing` and `onPong` have been removed, so expect compilation errors if
      overriding these two methods.
* If you implement `MuWebSocket` (without extending the base socket) you will need to convert to blocking versions
  of the websocket listening methods.


Mu 4 todo
---------

* Websocket permessage-deflate
* Better HTTP 103 Early Hints support (especially for creating links)
