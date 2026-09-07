
Breaking changes
================

* Minimum Java version is now 11
* Jakarta REST API is now 3.1; use an SLF4J 2.x logging provider.
* Public APIs have JSpecify nullness annotations.
* Deleted unused `io.muserver.Toggles` class
* REST exception responses use problem-details JSON by default. Check custom exception mappers,
  provider selection, URI encoding, matrix parameters and asynchronous resource results when upgrading.
* Writer interceptors now wrap actual serialization: code after `proceed()` runs after the entity writer.
* Graceful shutdown returns `true` on success and `false` when requests do not finish within the timeout.

New features
============

* Implemented Jakarta REST `Providers`, including explicit `ContextResolver` registration and provider-aware
  message body reader and writer factories.
* Application context injection, registration from a Jakarta REST `Application`, and Java SE bootstrap.
* WebJar resources, array-valued parameters, XML `Source` entity providers, and improved SSE lifecycle handling.

Bug fixes
=========

* Failed and cancelled `CompletionStage` resource results use exception mapping and release request resources.
* Request entity streams remain available through response serialization and close afterwards.
* HTTP/2 completion listeners wait for both request and response completion; resets and disconnects during
  unfinished uploads report failure. Request stream cleanup completes each pending buffer once.
* Empty root resource paths (`@Path("")` and `@Path("/")`) match method subpaths.
* URI decoding preserves UTF-8 characters, encoded separators and literal plus signs in resource filenames.

See the [3.0.0 migration guide](https://muserver.io/changelog/mu-server-3) for before/after examples and
the [Jakarta REST support matrix](src/main/java/io/muserver/rest/README.md) for supported scope.
The curated TCK harness does not establish full TCK conformance or certification.
