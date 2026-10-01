package io.muserver;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class QueryRequestValidationTest {
    @Test public void contentTypeRulesApplyToQueryOnBothHeaderImplementations() throws Exception {
        for (Headers headers : new Headers[]{new Http1Headers(), new Http2Headers()}) {
            assertThrows(InvalidHttpRequestException.class,
                () -> QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), headers));
            for (String value : new String[]{"text/plain", "application/json; charset=UTF-8"}) {
                headers.set("Content-Type", value);
                QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), headers);
            }
            for (String value : new String[]{"", "invalid", "text/*", "*/*", "text/plain; charset="}) {
                headers.set("Content-Type", value);
                assertThrows(InvalidHttpRequestException.class,
                    () -> QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), headers));
                QueryRequestValidation.validate(HttpMethod.GET, headers);
                QueryRequestValidation.validate(HttpMethod.POST, headers);
            }
            headers.set("Content-Type", "text/plain").add("Content-Type", "text/plain");
            assertThrows(InvalidHttpRequestException.class,
                () -> QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), headers));
        }
    }

    @Test public void emptyParametersAreValidButIncompleteNameValuePairsAreNot() throws Exception {
        for (String value : new String[]{"text/plain;", "text/plain;;;", "text/plain; ;charset=UTF-8;;"}) {
            QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new Http1Headers().set("Content-Type", value));
        }
        for (String value : new String[]{"text/plain; charset", "text/plain; charset=", "text/plain; =UTF-8"}) {
            org.junit.Assert.assertThrows(value, InvalidHttpRequestException.class, () ->
                QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new Http1Headers().set("Content-Type", value)));
        }
    }

    @Test public void validParametersIncludeQuotedSeparatorsEscapesAndLongValues() throws Exception {
        for (String value : new String[]{"text/plain", "text/plain" + ";".repeat(7000), "application/json; charset=UTF-8",
            "text/plain; profile=\"one;two,three\\\"four\\\\five\"", "text/plain; profile=\"" + "a".repeat(7000) + "\""}) {
            QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new Http1Headers().set("Content-Type", value));
        }
    }
}
