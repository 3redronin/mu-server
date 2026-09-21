# OpenAPI 3.1.2 migration

Mu Server v3 emits OpenAPI **3.1.2** for both manually built documents and JAX-RS documentation. Schema Objects use JSON Schema 2020-12. No production JSON parser or validator dependency is added.

## Presence, null, and defaults

Presence and nullability describe separate contracts. `withRequired(...)` on a parent object names properties that must exist. `withTypes(...)` on a property describes its allowed JSON types.

```java
SchemaObject nullableName = schemaObject()
    .withTypes(Arrays.asList("string", "null"))
    .build();
SchemaObject requiredNullableName = schemaObject()
    .withType("object")
    .withProperties(Collections.singletonMap("name", nullableName))
    .withRequired(Collections.singletonList("name"))
    .build();
```

Omit the parent `required` entry for an optional property; omit `"null"` from the type union for a non-null property. Adding `null` to `type` does **not** add it to an enum. Include `JsonNull.INSTANCE` in `withEnumValue(...)` when the enum itself permits null.

`JsonNull.INSTANCE` also represents an explicit JSON null in `withDefaultValue`, `withConstValue`, `withExample`, and `withExamples`. A Java `null` setter argument removes an optional field. Defaults are annotations: they neither insert missing values nor make values required or nullable.

JAX-RS primitives, boxed values, and collections no longer imply presence or nullability. Path parameters remain required. `@Required` is a documentation contract, with no new runtime enforcement. An absent primitive query parameter still uses the runtime's primitive default; `@DefaultValue` is documented only when explicitly supplied. Collection defaults are arrays. Form properties use the same rules.

`@BeanParam` runtime support is not present in this branch. This upgrade does not add it or claim that bean properties are accepted inputs; documentation flattening remains pending runtime support.

## Schema APIs

```java
SchemaObject price = schemaObject()
    .withType("number")
    .withExclusiveMinimumValue(new BigDecimal("0.00000000000000000001"))
    .withMultipleOfNumber(new BigDecimal("0.00000000000000000001"))
    .withExamples(Arrays.asList(new BigDecimal("1.23")))
    .build();
SchemaObject anything = booleanSchema(true).build();
SchemaObject nothing = booleanSchema(false).build();
SchemaObject reference = schemaObject().withRef("#/components/schemas/Price").build();
```

The `Number` setters (`withMaximumNumber`, `withMinimumNumber`, `withMultipleOfNumber`, and the exclusive-bound setters) preserve `BigDecimal`/`BigInteger` precision. The existing `Double` APIs remain available as numeric views. Non-finite numbers are rejected.

`withPatternText` accepts JSON Schema regular-expression text without compiling it as a Java `Pattern`. The old `withPattern(Pattern)` is an adapter. Use `patternText()` when a regex is not expressible in Java syntax.

The model includes identifiers, anchors, dynamic references, vocabularies, `$defs`, conditionals, dependent schemas and requirements, tuple/contains constraints, unevaluated properties/items, and content encoding/media-type/schema annotations. `withKeyword` supports vocabulary-specific custom keywords; `withExtension` requires an `x-` name. Schemas and nested JSON containers are immutable after `build()`. `toBuilder()` preserves all state.

Deprecated adapters:

| Existing API | Replacement / behavior |
|---|---|
| `withNullable(true)` | Use `withTypes`. The adapter adds `"null"` to an explicitly supplied type and never emits `nullable`. A missing type is an error. |
| `withExclusiveMinimum(true)` / `withExclusiveMaximum(true)` | Use numeric `withExclusiveMinimumValue` / `withExclusiveMaximumValue`. The adapter moves the associated inclusive bound; a missing bound is an error. |
| `withNot(List<SchemaObject>)` | Use `withNotSchema`. One entry becomes `not: schema`; multiple entries become `not: {anyOf: [...]}`; an empty list removes the restriction. |
| Schema `withExample` | Use schema `withExamples`. The legacy singular annotation is still legal and serialized. Parameter and media-type example APIs are unchanged. |
| `schemaObjectFrom(Class, Type, boolean required)` | Use `schemaObjectFrom(Class, Type)` or `schemaObjectFrom(Type)`. Presence no longer changes type inference. |
| Parameter `withAllowEmptyValue` | Prefer a schema describing empty strings, and `required` for presence. OpenAPI discourages this flag. It does not allow JSON null. |

`type()` cannot represent a union and throws `IllegalStateException` for multi-type schemas; use `types()`. Unknown Java payloads remain unconstrained. Arbitrary POJOs still need registered schemas or a customizer; their Java fields are not assumed to describe the wire representation. Byte/short values are integers. Raw binary bodies have unconstrained schemas rather than a JSON string type; explicitly encoded strings can use `contentEncoding` and `contentMediaType`.

## References and document fields

```java
ReferenceOr<RequestBodyObject> body = ReferenceOr.reference(
    ReferenceObjectBuilder.referenceObject()
        .withRef("#/components/requestBodies/Create")
        .withDescription("Create input")
        .build());
OperationObject operation = OperationObjectBuilder.operationObject()
    .withRequestBodyOrReferences(body)
    .build();
```

Reference-capable fields use `...OrReferences()` and `with...OrReferences(...)` consistently. Existing typed setters wrap inline values. Existing typed getters return inline values, but throw `IllegalStateException` if a reference cannot be represented. `ReferenceOr.inline(value)` supplies an inline alternative.

Reference Objects support `$ref`, `summary`, and `description`; they do not support extensions. A Schema Object's `$ref` is a JSON Schema keyword with sibling constraints. A Path Item's `$ref` belongs to the Path Item itself; it is not a Reference Object. These representations are intentionally distinct.

New fields include `jsonSchemaDialect`, `webhooks`, Info `summary`, License `identifier`, Components `pathItems`, Path Item `$ref`, Link `operationRef`, and security scheme type `mutualTLS`. License `identifier` and `url` are mutually exclusive. Documents may contain components or webhooks without paths; operation responses are optional; response maps may contain only `default`.

All extensible objects expose `withExtension` and `withExtensions`. Extension names must start with `x-`, preventing standard-field collisions. Reference and Security Requirement Objects do not accept extensions.

## Registration, generation, and merging

```java
Type strings = new jakarta.ws.rs.core.GenericType<List<String>>() {}.getType();
restHandler(resource).addCustomSchema(strings, "StringList",
    schemaObject().withType("array")
        .withItems(schemaObject().withType("string").build()).build());
```

Exact generic registrations take precedence over raw-class registrations. Unchanged registrations are emitted as component references. A customizer's changed schema is kept inline. Component names are deterministic; conflicting pre-existing manual components are retained and registrations get unused names.

Generation uses resolved generic types through arrays, collections, maps, inherited methods, `CompletionStage`, and `GenericEntity`. Opaque `Response` and suspended payloads stay unconstrained. Every effective consumes/produces type is included. Explicit examples take precedence over inferred examples; generated UUID/date examples are stable. Class response declarations apply unless overridden by the same method-level response code.

Manual root fields, components, tag descriptions, and paths survive generation. Manual operations win collisions, while generated non-conflicting operations remain. Overloads merge payload alternatives using deduplicated `anyOf`; inputs are required only when every alternative requires them. Bodyless statuses and HEAD operations have no documented content. Operations without bodies do not gain empty request bodies.

The HTML view understands type unions, boolean schemas, schema examples, and local references with cycle protection. External references are displayed without fetching them. Curl examples distinguish JSON bodies from form encodings and select one content alternative.

## Default omission and validation

A shared conservative whitelist omits parameter/header style and explode values only when they match their location-dependent defaults. It also omits default-false `deprecated`, `allowReserved`, and `allowEmptyValue` values in those objects. Encoding style/explode/allowReserved fields are retained because their presence can select a serialization mode. It does not blanket-strip false values or empty containers. Application defaults, schema keywords, examples, and `security: []` remain intact.

The executable [field checklist](../src/test/resources/openapi-3.1/field-coverage.tsv) is exercised by `OpenApi31FieldCoverageTest`. The offline resources include the official OpenAPI 3.1 schema and schema-base dated 2025-09-15, the OAS dialect dated 2024-11-10, and Draft 2020-12 metaschemas. Their exact SHA-256 digests are in `manifest.json`. Test-only NetworkNT 1.5.9 validates complete documents and representative generated-schema payloads; unknown resource fetches fail instead of reaching the network.

Baseline specifications: [OpenAPI 3.1.2](https://spec.openapis.org/oas/v3.1.2.html), [JSON Schema Core](https://json-schema.org/draft/2020-12/json-schema-core), and [JSON Schema Validation](https://json-schema.org/draft/2020-12/json-schema-validation).
