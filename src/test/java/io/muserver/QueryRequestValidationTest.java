package io.muserver;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;

public class QueryRequestValidationTest {
    @Test public void emptyParametersAreValidButIncompleteNameValuePairsAreNot() throws Exception {
        for (String value : new String[]{"text/plain;", "text/plain;;;", "text/plain; ;charset=UTF-8;;"}) {
            QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new DefaultHttpHeaders().set("Content-Type", value));
        }
        for (String value : new String[]{"text/plain; charset", "text/plain; charset=", "text/plain; =UTF-8"}) {
            org.junit.Assert.assertThrows(value, InvalidHttpRequestException.class, () ->
                QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new DefaultHttpHeaders().set("Content-Type", value)));
        }
    }

    @Test public void validParametersIncludeQuotedSeparatorsEscapesAndLongValues() throws Exception {
        for (String value : new String[]{"text/plain", "text/plain" + ";".repeat(7000), "application/json; charset=UTF-8",
            "text/plain; profile=\"one;two,three\\\"four\\\\five\"", "text/plain; profile=\"" + "a".repeat(7000) + "\""}) {
            QueryRequestValidation.validate(HttpMethod.valueOf("QUERY"), new DefaultHttpHeaders().set("Content-Type", value));
        }
    }
}
