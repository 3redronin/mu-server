package io.muserver.rest;

import io.muserver.Cookie;
import io.muserver.openapi.SchemaObjectBuilder;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.*;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;

/**
 * Documentation for Mu's built-in Java value representations.
 * SchemaObjectBuilder supplies general type/format defaults; this class adds Java syntax and
 * examples, with preferred temporal formats applied only to inputs.
 */
final class JavaValueSchemas {
    private JavaValueSchemas() { }

    static SchemaObjectBuilder parameter(Class<?> type, ParamConverter<?> converter) {
        if (!BuiltInParamConverterProvider.isBuiltIn(converter)) return schemaObject().withType("string");
        if (TemporalAccessor.class.isAssignableFrom(type) || TemporalAmount.class.isAssignableFrom(type)) {
            return temporalInput(type);
        }
        SchemaObjectBuilder schema = schemaObjectFrom(type);
        if (type.isEnum()) {
            java.util.List<String> values = converter instanceof ResourceMethodParam.HasAllowedValues
                ? ((ResourceMethodParam.HasAllowedValues) converter).allowedValues() : Collections.emptyList();
            return schema.withEnumValue(values.isEmpty() ? null : new java.util.ArrayList<Object>(values));
        }
        if (type == Date.class) return text(schema.withFormat(null), "A legacy date accepted by Date(String), including month names and a time zone; not an RFC 3339 parser.", "12 Feb 2021 15:33:28 GMT");
        if (type == Locale.class) return text(schema, "A language string passed to Locale(String); this is not parsed as a language tag.", "en");
        if (type == File.class) return text(schema, "A filesystem path passed to File(String).", "reports/example.txt");
        if (type == PathSegment.class) return text(schema, "A URI path segment with optional matrix parameters.", "books;lang=en");
        if (type == Cookie.class) return text(schema, "The value of the named request cookie.", "example");
        if (type == URI.class) return example(schema, "https://example.com/items/42");
        if (type == URL.class) return example(schema, "https://example.com/items/42");
        if (type == BigInteger.class) return example(schema, new BigInteger("123456789012345678901234567890"));
        if (type == BigDecimal.class) return example(schema, new BigDecimal("1234567890.123456789"));
        if (type == boolean.class || type == Boolean.class) return example(schema, true);
        if (type == char.class || type == Character.class) return text(schema, "The first character of the supplied value.", "A");
        if (type == String.class || type == StringBuilder.class || type == StringBuffer.class) return example(schema.withType("string"), "example");
        if ("integer".equals(schema.type())) return example(schema, 42);
        if ("number".equals(schema.type())) return example(schema, new BigDecimal("12.5"));
        return schema;
    }

    /** Preferred syntax for temporal parameters and built-in temporal request bodies. */
    static SchemaObjectBuilder temporalInput(Class<?> type) {
        SchemaObjectBuilder schema = temporalSchema(type);
        if (type == LocalTime.class) return schema.withFormat("time-local");
        if (type == LocalDateTime.class) return schema.withFormat("date-time-local");
        if (type == OffsetTime.class) return schema.withFormat("time");
        if (type == OffsetDateTime.class) return schema.withFormat("date-time");
        return schema;
    }

    /** Built-in temporal responses use Java's toString(), which may omit seconds or include a region ID. */
    static SchemaObjectBuilder temporalOutput(Class<?> type) {
        return temporalSchema(type);
    }

    private static SchemaObjectBuilder temporalSchema(Class<?> type) {
        SchemaObjectBuilder schema = schemaObjectFrom(type);
        if (type == Instant.class) return text(schema, "An ISO-8601 instant with a UTC offset and optional fractional seconds.", "2021-02-12T15:33:28.123Z");
        if (type == LocalDate.class) return text(schema, "An ISO-8601 calendar date without a time or zone.", "2021-02-12");
        if (type == OffsetDateTime.class) return text(schema, "An ISO-8601 date and time with an offset; seconds and fractional seconds are optional.", "2021-02-12T15:33:28.123+01:00");
        if (type == ZonedDateTime.class) return text(schema, "An ISO-8601 date and time with an offset and optional bracketed region ID; fractional seconds are optional.", "2021-02-12T15:33:28+01:00[Europe/Paris]");
        if (type == LocalDateTime.class) return text(schema, "An ISO-8601 local date and time without an offset or zone; seconds and fractional seconds are optional.", "2021-02-12T15:33:28.123");
        if (type == OffsetTime.class) return text(schema, "An ISO-8601 time with an offset; seconds and fractional seconds are optional.", "15:33:28.123+01:00");
        if (type == LocalTime.class) return text(schema, "An ISO-8601 local time without an offset or zone; seconds and fractional seconds are optional.", "15:33:28.123");
        if (type == Year.class) return text(schema, "An ISO-8601 year, with a sign for years outside the four-digit range.", "2021");
        if (type == YearMonth.class) return text(schema, "An ISO-8601 year and month without a day.", "2021-02");
        if (type == MonthDay.class) return text(schema, "An ISO-8601 month and day prefixed with two hyphens, without a year.", "--02-12");
        if (type == Duration.class) return text(schema, "An ISO-8601 duration accepted by Duration.parse, with days and time units; seconds may be fractional and components may be signed.", "PT1H30M");
        if (type == Period.class) return text(schema, "An ISO-8601 date period accepted by Period.parse, with years, months, weeks or days; components may be signed.", "P1Y2M3D");
        return schema;
    }

    private static SchemaObjectBuilder text(SchemaObjectBuilder schema, String description, String example) {
        return example(schema.withType("string").withDescription(description), example);
    }

    private static SchemaObjectBuilder example(SchemaObjectBuilder schema, Object example) {
        return schema.withExamples(Collections.singletonList(example));
    }
}
