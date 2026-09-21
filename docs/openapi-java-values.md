# Java value schemas

Generated documents remain OpenAPI 3.1.2. Ordinary query, path, matrix, header,
cookie and form parameters now share registration and customization precedence:
infer the effective converter's representation (or use the exact generic
registration before the raw-class registration), apply explicit documentation,
then invoke the schema customizer. Unchanged registrations remain component
references; customized variants are inline. Parameter customizers receive
`PARAMETER`, `parameterName()` and `parameterLocation()`; form customizers retain
`FORM_PARAM` and now also receive their location.

Built-in parameter converters provide fixed examples for scalars, UUID, URI,
URL, Java time values, legacy dates, SQL dates/times, locales and filesystem
paths. Java-specific temporal syntax uses descriptions rather than custom
formats or patterns. SQL timestamps use a space separator. `Date(String)` is a
legacy parser, not an RFC 3339 parser. `Locale(String)` consumes a language
string, not a parsed language tag. Enum restrictions come from the effective
converter; custom converters receive an unconstrained string schema unless a
schema is registered or customized. No parameter converters were added.

Presence, nullability and defaults remain independent. Collection defaults are
arrays and collection serialization settings are preserved. Item registrations
are used within parameter collections. Explicit examples and descriptions take
precedence over inferred annotations.

Parameter conversion and entity serialization are separate contracts. Reader
and character-array entities are text, whereas byte-array parameters have array
schemas. Binary entity schemas retain the existing OpenAPI 3.1 representation
(an unconstrained schema, with the content media type describing the bytes).
For selected custom entity providers, register or customize the schema; their
serialization cannot be inferred from the Java type. Built-in temporal entity
providers share the temporal descriptions only when selected for the media
type. Existing structural fallback inference for containers without a matching
provider is retained for compatibility; it does not add a runtime serializer.
