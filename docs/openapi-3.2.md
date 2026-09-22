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

## Behavioral audit for PR 251

The field TSV is **preservation coverage**, not proof of contextual validity or
presentation. The following is the separate behavioral checklist. Test class
names refer to `src/test/java/io/muserver/{openapi,rest}`. Sources are sections
of the [normative 3.2.1 specification](https://spec.openapis.org/oas/v3.2.1.html).
Generation precedence and curl selection are library policies; the specification
supplies the meaning of the generated contracts, not Java annotation precedence.

| Behavior and normative source | Positive, negative and interaction coverage |
| --- | --- |
| Version, `$self` and URI fragments ([OpenAPI Object](https://spec.openapis.org/oas/v3.2.1.html#openapi-object)) | `OpenApi32ModelTest.selfPreservesValidUriReferencesThroughCopyingAndSerialization`, `selfRejectsFragmentsIncludingEmptyFragments`, `selfRejectsMalformedUriReferences`; reference resolution below. |
| Server name and tag summary/parent/kind ([Server Object](https://spec.openapis.org/oas/v3.2.1.html#server-object), [Tag Object](https://spec.openapis.org/oas/v3.2.1.html#tag-object)) | `OpenApi32BehaviorTest.serverMetadataDoesNotAlterUrlTemplatesAndEmptyNamesArePreserved`, `tagHierarchyRejectsMissingParentsAndCyclesButAllowsForwardDeclarations`: absent/empty, snapshots, forward parents, missing parents, self and multi-tag cycles. |
| QUERY, additional operations, casing and IDs ([Path Item Object](https://spec.openapis.org/oas/v3.2.1.html#path-item-object), [Operation Object](https://spec.openapis.org/oas/v3.2.1.html#operation-object)) | `OpenApi32ReviewRegressionTest.standardAndLowercaseAdditionalOperationsBothRender`, `additionalOperationCasingSurvivesCurlAndHeadings`, `generatedIdsAvoidAdditionalOperationIds`, `curlPreservesShellMetacharactersInCustomMethods`; existing path validation and field tests retain invalid method checks. |
| Parameter locations, defaults and querystring exclusion ([Parameter Object](https://spec.openapis.org/oas/v3.2.1.html#parameter-object)) | `OpenApi32CookieTest` tests all three cookie styles with absent/true/false explode and JSON omission; `OpenApi32ModelTest.querystringConflictsAreCheckedAcrossPathAndOperation`; `OpenApi31DocumentTest.contextSensitiveValidationAndDefaults`; `OpenApiParameterContextTest` retains runtime/context checks. |
| Every new media reference position ([Components Object](https://spec.openapis.org/oas/v3.2.1.html#components-object), [Reference Object](https://spec.openapis.org/oas/v3.2.1.html#reference-object)) | `OpenApi32BehaviorTest.newReferencePositionsKeepSnapshotsAndFailClearlyForInlineAccess` covers components, request bodies, responses, parameters and headers, immutable snapshots, copies, inline-access errors and conflicting merges; `OpenApi32MediaTypeKeyTest` covers valid/invalid inline and reference component names. |
| Local and `$self`-relative references ([Relative References](https://spec.openapis.org/oas/v3.2.1.html#relative-references-in-api-description-uris)) | `OpenApi32ReferencesTest` covers local, absolute and relative targets, pointer escapes, percent-encoded spaces, literal plus, missing/malformed/external targets and cycles. `OpenApi31HtmlTest.localCyclicAndExternalReferencesAndBooleanUnionsRender` retains presentation coverage. No network resolution. |
| Examples: parsed versus serialized, empty/false/null, conflicts ([Example Object](https://spec.openapis.org/oas/v3.2.1.html#example-object)) | `OpenApi32BehaviorTest.examplesPreserveFalseEmptyAndNullButRejectConflictingRepresentations`; `OpenApi32PresentationTest.referencedSerializedBodyOverridesSchemaExampleWithoutShellExpansion`; querystring precedence/reference regressions remain in `OpenApi32ReviewRegressionTest`. |
| XML compatibility ([XML Object](https://spec.openapis.org/oas/v3.2.1.html#xml-object)) | `OpenApi32BehaviorTest.xmlNodeTypesRejectEveryExplicitLegacyFlag` tests all five node types against both legacy flags and both boolean values; invalid node type remains in `OpenApi32ModelTest`. |
| Discriminator fallback ([Discriminator Object](https://spec.openapis.org/oas/v3.2.1.html#discriminator-object)) | `OpenApi32BehaviorTest.discriminatorFallbackIsAnAnnotationAndDoesNotChangeValidation` checks accepted/rejected payloads as well as preservation. Mapping is a hint, not a runtime deserializer or an alternative to JSON Schema validation. |
| Named, positional, item and nested encoding ([Encoding Object](https://spec.openapis.org/oas/v3.2.1.html#encoding-object)) | `OpenApi32BehaviorTest.encodingCombinationsAreCheckedEvenForEmptyCollectionsAndAreImmutable` covers all eight presence combinations at media and nested encoding levels; `OpenApi32ModelTest.streamingFixturesKeepWholeStreamAndItemContracts` exercises streaming media types and merges. |
| Response summaries, optional/empty descriptions and alternatives ([Response Object](https://spec.openapis.org/oas/v3.2.1.html#response-object), [Media Type Object](https://spec.openapis.org/oas/v3.2.1.html#media-type-object)) | `OpenApi32ReviewRegressionTest.missingResponseDescriptionsAreBackfilledWithoutReplacingExplicitEmptyValues`; `OpenApi31IntegrationTest.overloadsMergeWithoutEmptyBodiesOrLostSchemas`; referenced merge rejection above. |
| OAuth slot rules and metadata ([OAuth Flow Object](https://spec.openapis.org/oas/v3.2.1.html#oauth-flow-object), [Security Scheme Object](https://spec.openapis.org/oas/v3.2.1.html#security-scheme-object)) | `OpenApi32OAuthTest.urlsAreValidatedInEveryContainingSlot`: all 40 slot/URL-presence combinations, required and forbidden fields, complete documents, refresh URLs, scopes, extensions and copies. `scopesAndExtensionsAreImmutableSnapshotsAndEmptyFlowsAreValid` and `OpenApi32BehaviorTest.securityMetadataIsContextualAndExplicitFalseSurvives` cover snapshots, absent/empty and false. |
| Generation precedence, registrations and customizers (library policy) | `OpenApi31IntegrationTest.exactGenericRegistrationAndManualRootContentSurvive`, `customizersInlineChangedRegisteredSchemas`, `manualReferencedOperationsWinGeneratedCollisions`; `OpenApi32SseContractTest.manualOperationsOwnGeneratedSseContracts`; interface/class regressions and method customizer tests in `OpenApi32ReviewRegressionTest` and `OpenApi32SseTest`. |
| SSE detection, statuses, alternatives and payload references ([Media Type Object](https://spec.openapis.org/oas/v3.2.1.html#media-type-object)) | `OpenApi32SseContractTest.sourcesAlternativesReferencesAndExplicitResponsesInteract` covers sink/context, mixed named/unnamed alternatives, invalid data/retry, registered payloads and all bodyless statuses; `duplicatesFailBeforeDocumentPublication`; `OpenApi32SseStatusTest` covers explicit/default statuses and HEAD, including `detectedSseDoesNotInventStatusesBesideExplicitResponseAnnotations`; parameterized media and whole-stream preservation regressions remain. |
| Parsed SSE and separately decoded JSON ([Sequential Media Types](https://spec.openapis.org/oas/v3.2.1.html#sequential-media-types)) | `OpenApi32SseTest.realFramesValidateAsParsedItemsAndDecodedJsonBeforeClosure` retains bounded real delivery, parsed frame validation and explicit JSON payload decoding. |
| Effective parameters, example precedence and executable curl (library presentation policy; Parameter/Example Objects above) | `OpenApi32PresentationTest.effectiveParametersAndExamplePrecedenceReachExecutableCurlArguments` checks path inheritance and operation override by name/location; `serializedQueryExamplesAreNotEncodedTwice` and `mediaTypeParametersAreSingleShellArguments` add wire-encoding and header quoting cases; all presentation tests execute actual generated shell commands against a local curl stub and compare every argument. Existing regressions execute unusual method and raw-query examples. |

### Schema versus semantic validation

Official resources and their checksums remain unchanged. The pinned structural
schema accepts `explode: false` on cookie parameters; normative section 4.12.2.2
forbids it for both `form` and `cookie` styles. The same section gives both styles a
true default. Semantic tests therefore reject explicit false while keeping true
omitted from JSON. The review suggestion to switch the default to false is not
applied.

OAuth flow objects have no independent flow type. The offline validator now
requires a containing `OAuthFlowsObject` instead of selecting a schema from URL
fields. Field-preservation fixtures choose their slot explicitly. Complete valid
OAuth documents are also validated offline.

Structural validation does not resolve every reference, enforce tag hierarchy
acyclicity, interpret discriminator mappings, or decode JSON inside SSE data.
Those responsibilities have separate tests above. HTML resolves only local
references and leaves unresolved/external references visible; it does not fetch
external examples or schemas.

### Audit fixes and reproduction

Before fixes, `OpenApi32CookieTest.explicitFalseIsInvalidForEveryCookieStyle` and
`OpenApi32OAuthTest.urlsAreValidatedInEveryContainingSlot` failed. The initial
`OpenApi32PresentationTest` cases failed with lost inherited parameters and
schema examples replacing named examples. The escaped-pointer test failed on
`space%20key`. Each now passes with the corresponding fix. Earlier review
regressions are retained, and new tests are grouped by behavior.

Further interaction tests reproduced an extra 200 beside explicit detected-SSE
statuses, double-encoded serialized queries, and split shell arguments for media
type parameters containing apostrophes. The SSE helper now augments existing
response codes, serialized queries retain their wire representation, and media
type header arguments use the shared shell-escaping helper.

### Final verification (2026-09-22)

All eight full `clean verify` configurations passed: Java 11, 17, 21 and 25,
each with Netty 4.1 and 4.2. Each ran 1,521 tests with zero failures, errors or
skips. Java 21 included the existing NullAway profile on both Netty versions.
The configurations used isolated source snapshots checked byte-for-byte against
the final sources; no pinned schema resource changed.

The separate showcase was rebuilt against the final library. Chromium passed
all operations, 44 query types, 11 feature panels, temporal query/form inputs,
payments, uploads, boolean schemas, framed JSON and text/JSON/mixed/broadcast
SSE start/stop. HTTP smoke checks passed. Both `/openapi.json` and
`/api/features/document` passed the pinned offline validator. Its illustrative
cookie parameter now uses the normative true explode default.

### Form serialized-example follow-up

OAS 3.2.1 §4.19.2.2 defines a media type's `serializedValue` as the serialized
media document with encoding effects already applied. Curl now prefers that
explicit wire body even when a form schema defines properties, while retaining
the property documentation. It does not also append property-based form fields.
`OpenApi32PresentationTest.serializedFormExamplesOverridePropertyExpansionIncludingEmptyValuesAndReferences`
reproduced the review finding before the fix and covers both form media types,
inline/referenced examples and explicit empty values. The companion
`formsWithoutSerializedExamplesStillExpandProperties` and
`serializedMultipartExampleKeepsItsBoundaryAndFraming` checks cover fallback
and exact multipart framing. All 26 presentation/review regression tests pass
on Java 21 / Netty 4.2 with NullAway. This follow-up used targeted verification;
the eight-configuration matrix and showcase results above describe the preceding
audit revision.
