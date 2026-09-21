# Offline OpenAPI 3.1 validation resources

These files are unmodified downloads of the official schemas. `manifest.json` records their canonical identifiers, filenames, and SHA-256 hashes. `OfflineOpenApiValidator` verifies these hashes and rejects requests for any unbundled schema.

- OpenAPI schema and schema-base: https://spec.openapis.org/oas/3.1/schema/2025-09-15 and https://spec.openapis.org/oas/3.1/schema-base/2025-09-15
- OpenAPI dialect and annotation vocabulary: https://spec.openapis.org/oas/3.1/dialect/2024-11-10 and https://spec.openapis.org/oas/3.1/meta/2024-11-10
- JSON Schema Draft 2020-12: https://json-schema.org/draft/2020-12/schema and its seven referenced vocabulary metaschemas

The OpenAPI resources are maintained by the OpenAPI Initiative in [OpenAPI-Specification](https://github.com/OAI/OpenAPI-Specification). The JSON Schema resources are maintained by the JSON Schema project in [json-schema-spec](https://github.com/json-schema-org/json-schema-spec).

`field-coverage.tsv` is Mu Server's field checklist, exercised by `OpenApi31FieldCoverageTest`. It covers the fixed fields, context-dependent parameter/security fields, patterned containers, and JSON Schema keywords. Separate regression tests exercise boolean schemas, extensions, reference positions, compatibility adapters, validation failures, and contextual default omission. The checklist is intentionally independent of Java reflection discovery so a missing builder or getter fails the test.
