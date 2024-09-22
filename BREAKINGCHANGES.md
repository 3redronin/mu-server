Version 3
=========

* `SsePublisher.start()` no longer puts the request in async mode. `AsyncSsePublisher.start()` can be used instead.
* `SsePublisher` now implements `Closeable` and so the `close()` method now throws an `IOException`.