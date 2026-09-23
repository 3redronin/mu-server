package io.muserver.rest;

import io.muserver.openapi.SchemaObject;
import jakarta.ws.rs.ext.ParamConverter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class JavaValueSchemasTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> types() {
        return Arrays.asList(new Object[][] {
            {java.time.Instant.class}, {java.time.LocalDate.class}, {java.time.OffsetDateTime.class},
            {java.time.ZonedDateTime.class}, {java.time.LocalDateTime.class}, {java.time.OffsetTime.class},
            {java.time.LocalTime.class}, {java.time.Year.class}, {java.time.YearMonth.class},
            {java.time.MonthDay.class}, {java.time.Duration.class}, {java.time.Period.class},
            {java.util.Date.class},
            {java.util.UUID.class}, {java.net.URI.class}, {java.net.URL.class}, {java.util.Locale.class},
            {java.io.File.class}, {jakarta.ws.rs.core.PathSegment.class}, {java.math.BigInteger.class},
            {java.math.BigDecimal.class}, {int.class}, {long.class}, {short.class}, {byte.class},
            {double.class}, {float.class}, {boolean.class}, {char.class}, {String.class}, {StringBuilder.class}
        });
    }

    @Parameterized.Parameter public Class<?> type;

    @Test public void fixedExampleIsAcceptedByTheActualConverter() {
        ParamConverter<?> converter = new BuiltInParamConverterProvider().getConverter(type, type, new Annotation[0]);
        assertNotNull(converter);
        SchemaObject schema = JavaValueSchemas.parameter(type, converter).build();
        assertNotNull(schema.type());
        assertNotNull(schema.examples());
        assertNotNull(converter.fromString(String.valueOf(schema.examples().get(0))));
        assertEquals(schema.toString(), JavaValueSchemas.parameter(type, converter).build().toString());
    }

    @Test public void customConverterDoesNotInheritJavaSyntaxConstraints() {
        ParamConverter<Object> custom = new ParamConverter<Object>() {
            public Object fromString(String value) { return value; }
            public String toString(Object value) { return value.toString(); }
        };
        SchemaObject schema = JavaValueSchemas.parameter(type, custom).build();
        assertEquals("string", schema.type());
        assertNull(schema.format());
        assertNull(schema.examples());
        assertNull(schema.enumValue());
    }
}
