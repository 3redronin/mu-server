# OpenAPI 3.2.1 and streaming documentation

Manual and generated documents use OpenAPI 3.2.1. `jsonSchemaDialect` remains
omitted by default. Runtime routing, parameter conversion, entity providers and
SSE delivery are unchanged. QUERY is available in manually built path items;
generation only documents Mu's existing HTTP methods. CONNECT is represented
under `additionalOperations`.

## Model checklist

The executable field checklist in
`src/test/resources/openapi-3.1/field-coverage.tsv` includes the existing model
and every new 3.2 field. It checks builders, getters, serialization, copies and
validation against pinned official schemas. New fields include:

- Document `$self`, server `name`, tag `summary`, `parent` and `kind`.
- Path `query` and `additionalOperations`; parameter `querystring` and cookie
  style; path `allowReserved` and contextual query/querystring exclusion.
- Reusable component media types and reference-capable content maps on request
  bodies, responses, parameters and headers.
- Media type descriptions, `itemSchema`, positional `prefixEncoding` and
  `itemEncoding`; nested encoding.
- Response summaries and optional descriptions; example `dataValue` and
  `serializedValue`; discriminator `defaultMapping`; XML `nodeType`.
- OAuth device authorization flow and URL, security scheme metadata URL and
  deprecation.

Use `contentOrReferences()` / `withContentOrReferences()` for content containing
references. Existing inline content accessors throw clearly when a reference
cannot be represented. Component media types follow the same inline/reference
API convention. Merging distinct referenced alternatives requires resolving
those references first; a merge fails explicitly rather than discarding them.

Legacy example `value` and XML `attribute`/`wrapped` APIs remain available.
Prefer `dataValue` for schema-valid data and `serializedValue` for the wire
representation. `value` cannot coexist with these fields. `nodeType` cannot
coexist with either legacy XML flag, even when explicitly false.

## Server-sent events

```java
@ApiSseEvent(name = "price", data = Price.class, mediaType = "application/json")
```

The annotation is repeatable on classes and methods. Defaults are an unnamed
String event, text/plain, no description, and response code 200. Method
annotations replace class declarations with the same status/event pair;
duplicates at one level are rejected. Explicit response annotations and manual
operations retain authority. Register payload schemas with `addCustomSchema`.

SSE sinks/context and text/event-stream declarations also trigger a generic
item schema. A void SSE method documents 200, not 204. HEAD and bodyless status
codes retain their restrictions.

`itemSchema` describes parsed events: string data, optional event/id and a
nonnegative integer retry. JSON data remains a string, with `contentMediaType`
and `contentSchema` describing its decoded contents. Consumers must explicitly
decode JSON and validate it; ordinary JSON Schema validation does not perform
that decoding. Comments and heartbeat frames are not payload events.

`RESPONSE_ITEM` customizers receive the complete event schema and optional
`eventName()`, `payloadType()` and `payloadMediaType()` context. Generic payload
contracts can use registered schemas or manual builders.

## Other streaming formats

Whole-stream `schema` and per-item `itemSchema` can coexist:

```java
MediaTypeObjectBuilder.mediaTypeObject()
    .withSchema(SchemaObjectBuilder.schemaObject().withType("string").build())
    .withItemSchema(SchemaObjectBuilder.schemaObject().withType("object").build())
    .build();
```

Use this media object under application/jsonl, application/x-ndjson or
application/json-seq as appropriate. Multipart streams can additionally use
`withPrefixEncoding(...)` and `withItemEncoding(...)`; these cannot be combined
with named `encoding`. Executable fixtures are in `OpenApi32ModelTest`.
No new serializers are installed by these documentation builders.

HTML documentation resolves local references (including references relative to
`$self`) with cycle protection, presents parsed/serialized examples and item
schemas, and uses `curl -N` for streaming responses.

## Offline conformance resources

`src/test/resources/openapi-3.2/manifest.json` records source URLs and SHA-256
checksums for official schema/schema-base resources dated 2026-08-30 and
meta/dialect resources dated 2026-02-26. JSON Schema 2020-12 resources are shared
with the existing 3.1 suite. The validator refuses unbundled network resources;
all validation dependencies remain test-only.

Normative specification: https://spec.openapis.org/oas/v3.2.1.html
