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
