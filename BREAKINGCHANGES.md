Version 3
=========

* `SsePublisher.start()` no longer puts the request in async mode. `AsyncSsePublisher.start()` can be used instead.
* `SsePublisher` now implements `Closeable` and so the `close()` method now throws an `IOException`.
* Disconnected clients are only detected when request read and writes occur (or if the server's connection idle timeout occurs). 
This is most likely to affect long-running HTTP1 connections, for example SSE connections that do not do much writing.
The best way to detect disconnected clients is to do frequent IO calls - e.g. for SSE connections write comments periodically.
