package io.muserver;

import org.junit.jupiter.api.Test;

public class QueryRequestValidationTest {
    @Test public void emptyParametersAreValidButIncompleteNameValuePairsAreNot() throws Exception {
        for (String value : new String[]{"text/plain;", "text/plain;;;", "text/plain; ;charset=UTF-8;;"}) {
            QueryRequestValidation.validate(Method.QUERY, headers(value));
        }
        for (String value : new String[]{"text/plain; charset", "text/plain; charset=", "text/plain; =UTF-8"}) {
            org.junit.jupiter.api.Assertions.assertThrows(HttpException.class, () ->
                QueryRequestValidation.validate(Method.QUERY, headers(value)));
        }
    }

    @Test public void validParametersIncludeQuotedSeparatorsEscapesAndLongValues() throws Exception {
        for (String value : new String[]{"text/plain", "text/plain" + ";".repeat(7000), "application/json; charset=UTF-8",
            "text/plain; profile=\"one;two,three\\\"four\\\\five\"", "text/plain; profile=\"" + "a".repeat(7000) + "\""}) {
            QueryRequestValidation.validate(Method.QUERY, headers(value));
        }
    }

    private static FieldBlock headers(String contentType) {
        FieldBlock headers = new FieldBlock();
        headers.add(HeaderNames.CONTENT_TYPE, contentType);
        return headers;
    }
}
