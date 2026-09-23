package io.muserver;

import java.util.List;
import java.util.regex.Pattern;

/** Shared transport validation, before dispatch or an informational response. */
final class QueryRequestValidation {
    // Possessive repetition keeps validation bounded even for long quoted parameter values.
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]++";
    private static final String QUOTED = "\"(?:[\\t !#-\\[\\]-~\\x80-\\xFF]|\\\\[\\t !-~\\x80-\\xFF])*+\"";
    // RFC 9110 section 5.6.6 permits empty parameter entries: *( OWS ";" OWS [ parameter ] ).
    private static final Pattern CONTENT_TYPE = Pattern.compile(
        "[\\t ]*+" + TOKEN + "/" + TOKEN + "[\\t ]*+(?:;[\\t ]*+(?:" + TOKEN + "=(?:" + TOKEN + "|" + QUOTED + "))?[\\t ]*+)*+");

    static void validate(Method method, FieldBlock headers) throws HttpException {
        if (method != Method.QUERY) return;
        List<String> values = headers.getAll(HeaderNames.CONTENT_TYPE);
        if (values.size() != 1 || !CONTENT_TYPE.matcher(values.get(0)).matches()) {
            throw new HttpException(HttpStatus.BAD_REQUEST_400, "400 Bad Request - QUERY requires a valid Content-Type");
        }
        String mediaRange = values.get(0).split(";", 2)[0];
        if (mediaRange.indexOf('*') >= 0) {
            throw new HttpException(HttpStatus.BAD_REQUEST_400, "400 Bad Request - QUERY requires a concrete Content-Type");
        }
    }

}
