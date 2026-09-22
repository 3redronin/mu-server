# Offline OpenAPI validation resources

The JSON schemas are unmodified official resources used by the tests.
`manifest.json` maps their canonical URLs to local filenames and SHA-256 hashes.
`OfflineOpenApiValidator` checks those hashes and rejects unbundled schema requests
instead of downloading them.

- `schema-*` and `schema-base-*` describe OpenAPI document structure.
- `dialect-*` and `meta-*` describe Schema Objects and OpenAPI annotations.
- `draft-2020-12-*` supply the shared JSON Schema metaschema and its seven vocabularies.

The OpenAPI resources come from the OpenAPI Initiative; the Draft 2020-12
resources come from the JSON Schema project. Exact source URLs are recorded in
the manifest. The current OpenAPI resources target 3.2 documents.

`field-coverage.tsv` is the library's field checklist. `OpenApi31FieldCoverageTest`
reads each row to check builders, getters, JSON serialization and copying.
The class name is historical; the checklist includes the current model fields.
Behavioral tests separately check defaults, contextual rules and interactions.
