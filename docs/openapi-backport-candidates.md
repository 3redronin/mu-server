# Possible 2.x OpenAPI bug backports

Only the v3 checkout is changed by this upgrade. The following defects were also confirmed by inspecting the maintenance/2.x checkout and can be considered independently:

- `Jsonizer` does not escape every U+0000–U+001F control character, serializes only `List` containers, and accepts non-finite numbers.
- `SchemaObject` casts arbitrary enum values to Java enums, and serializes `not` as an array instead of a schema.
- `ParameterObject` and `HeaderObject` assume `explode: true` when no style is given, including locations whose default style is `simple`.
- `OpenApiDocumentor` mutates the existing component schema map while merging registrations; built maps are immutable.

Backport fixes should retain the 2.x OpenAPI 3.0 dialect. In particular, do not backport numeric exclusive-bound output, type unions, removal of the 3.0 `nullable` keyword, or the 3.1 document version as isolated bug fixes. Each backport needs its own compatibility tests.
