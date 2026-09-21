package io.muserver.rest;

import io.muserver.openapi.SchemaObjectBuilder;
import jakarta.ws.rs.ext.ParamConverter;

import java.util.Collections;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;

/** Wire representations accepted by Mu's built-in parameter converters. */
final class JavaValueSchemas {
    private JavaValueSchemas() { }

    static SchemaObjectBuilder parameter(Class<?> type, ParamConverter<?> converter) {
        if (!BuiltInParamConverterProvider.isBuiltIn(converter)) return schemaObject().withType("string");
        SchemaObjectBuilder schema = schemaObjectFrom(type);
        if (type.isEnum()) {
            java.util.List<String> values = converter instanceof ResourceMethodParam.HasAllowedValues
                ? ((ResourceMethodParam.HasAllowedValues) converter).allowedValues() : Collections.emptyList();
            return schema.withEnumValue(values.isEmpty() ? null : new java.util.ArrayList<Object>(values));
        }
        switch (type.getName()) {
            case "java.time.Instant": return text(schema, "An ISO-8601 instant with a UTC offset and optional fractional seconds.", "2021-02-12T15:33:28.123Z");
            case "java.time.LocalDate": return text(schema, "An ISO-8601 calendar date without a time or zone.", "2021-02-12");
            case "java.time.OffsetDateTime": return text(schema, "An ISO-8601 date and time with an offset; seconds and fractional seconds are optional.", "2021-02-12T15:33:28.123+01:00");
            case "java.time.ZonedDateTime": return text(schema, "An ISO-8601 date and time with an offset and optional bracketed region ID; fractional seconds are optional.", "2021-02-12T15:33:28+01:00[Europe/Paris]");
            case "java.time.LocalDateTime": return text(schema, "An ISO-8601 local date and time without an offset or zone; seconds and fractional seconds are optional.", "2021-02-12T15:33:28.123");
            case "java.time.OffsetTime": return text(schema, "An ISO-8601 time with an offset; seconds and fractional seconds are optional.", "15:33:28.123+01:00");
            case "java.time.LocalTime": return text(schema, "An ISO-8601 local time without an offset or zone; seconds and fractional seconds are optional.", "15:33:28.123");
            case "java.time.Year": return text(schema, "An ISO-8601 year, with a sign for years outside the four-digit range.", "2021");
            case "java.time.YearMonth": return text(schema, "An ISO-8601 year and month without a day.", "2021-02");
            case "java.time.MonthDay": return text(schema, "An ISO-8601 month and day prefixed with two hyphens, without a year.", "--02-12");
            case "java.time.Duration": return text(schema, "An ISO-8601 duration accepted by Duration.parse, with days and time units; seconds may be fractional and components may be signed.", "PT1H30M");
            case "java.time.Period": return text(schema, "An ISO-8601 date period accepted by Period.parse, with years, months, weeks or days; components may be signed.", "P1Y2M3D");
            case "java.util.Date": return text(schema.withFormat(null), "A legacy date accepted by Date(String), including month names and a time zone; not an RFC 3339 parser.", "12 Feb 2021 15:33:28 GMT");
            case "java.sql.Date": return text(schema.withFormat(null), "A JDBC date accepted by java.sql.Date.valueOf, in year-month-day form.", "2021-02-12");
            case "java.sql.Time": return text(schema.withFormat(null), "A JDBC time accepted by java.sql.Time.valueOf, in hour:minute:second form.", "15:33:28");
            case "java.sql.Timestamp": return text(schema.withFormat(null), "A JDBC timestamp with a space between the date and time, and optional fractional seconds.", "2021-02-12 15:33:28.123456789");
            case "java.util.Locale": return text(schema, "A language string passed to Locale(String); this is not parsed as a language tag.", "en");
            case "java.io.File": return text(schema, "A filesystem path passed to File(String).", "reports/example.txt");
            case "jakarta.ws.rs.core.PathSegment": return text(schema, "A URI path segment with optional matrix parameters.", "books;lang=en");
            case "io.muserver.Cookie": return text(schema, "The value of the named request cookie.", "example");
            case "java.net.URI": return example(schema, "https://example.com/items/42");
            case "java.net.URL": return example(schema, "https://example.com/items/42");
            case "java.math.BigInteger": return example(schema, new java.math.BigInteger("123456789012345678901234567890"));
            case "java.math.BigDecimal": return example(schema, new java.math.BigDecimal("1234567890.123456789"));
            default:
                if (type == boolean.class || type == Boolean.class) return example(schema, true);
                if (type == char.class || type == Character.class) return text(schema, "The first character of the supplied value.", "A");
                if (type == String.class || type == StringBuilder.class || type == StringBuffer.class) return example(schema.withType("string"), "example");
                if ("integer".equals(schema.type())) return example(schema, 42);
                if ("number".equals(schema.type())) return example(schema, new java.math.BigDecimal("12.5"));
                return schema;
        }
    }

    private static SchemaObjectBuilder text(SchemaObjectBuilder schema, String description, String example) {
        return example(schema.withType("string").withDescription(description), example);
    }

    private static SchemaObjectBuilder example(SchemaObjectBuilder schema, Object example) {
        return schema.withExamples(Collections.singletonList(example));
    }
}
