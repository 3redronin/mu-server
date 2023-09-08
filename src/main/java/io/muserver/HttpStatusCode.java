package io.muserver;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HttpStatusCode {

    private final int code;
    private final String reason;

    HttpStatusCode(int code, String reason) {
        if (code < 100 || code > 999) throw new IllegalArgumentException("Status codes must be 3 digits");
        this.code = code;
        this.reason = reason;
    }

    public int code() {
        return code;
    }

    public String reasonPhrase() {
        return reason;
    }

    public boolean isInformational() {
        return code / 100 == 1;
    }
    public boolean isSuccessful() {
        return code / 100 == 2;
    }
    public boolean isRedirection() {
        return code / 100 == 3;
    }
    public boolean isClientError() {
        return code / 100 == 4;
    }
    public boolean isServerError() {
        return code / 100 == 5;
    }

    byte[] http11ResponseLine() {
        return (HttpVersion.HTTP_1_1.version() + " " + code + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    boolean noContentLengthHeader() {
        return isInformational() || code == 204 || code == 304 || code == 205;
    }

    public boolean sameCode(HttpStatusCode other) {
        Mutils.notNull("other", other);
        return other.code == this.code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttpStatusCode that = (HttpStatusCode) o;
        return code == that.code && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, reason);
    }

    @Override
    public String toString() {
        return code + " " + reason;
    }

    public static HttpStatusCode of(int code) {
        var c = predefined.get(code);
        return c != null ? c : new HttpStatusCode(code, "Unspecified");
    }
    public static HttpStatusCode of(int code, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("No reason code given");
        if (!reason.matches("[A-Za-z0-9 -_]+")) throw new IllegalArgumentException("Invalid reason phrase");
        var c = predefined.get(code);
        if (c != null && c.reason.equals(reason)) return c;
        return new HttpStatusCode(code, reason);
    }

    public static HttpStatusCode CONTINUE_100 = new HttpStatusCode(100, "Continue");
    public static HttpStatusCode SWITCHING_PROTOCOLS_101 = new HttpStatusCode(101, "Switching Protocols");
    public static HttpStatusCode PROCESSING_102 = new HttpStatusCode(102, "Processing");
    public static HttpStatusCode EARLY_HINTS_103 = new HttpStatusCode(103, "Early Hints");
    public static HttpStatusCode OK_200 = new HttpStatusCode(200, "OK");
    public static HttpStatusCode CREATED_201 = new HttpStatusCode(201, "Created");
    public static HttpStatusCode ACCEPTED_202 = new HttpStatusCode(202, "Accepted");
    public static HttpStatusCode NON_AUTHORITATIVE_INFORMATION_203 = new HttpStatusCode(203, "Non-Authoritative Information");
    public static HttpStatusCode NO_CONTENT_204 = new HttpStatusCode(204, "No Content");
    public static HttpStatusCode RESET_CONTENT_205 = new HttpStatusCode(205, "Reset Content");
    public static HttpStatusCode PARTIAL_CONTENT_206 = new HttpStatusCode(206, "Partial Content");
    public static HttpStatusCode MULTI_STATUS_207 = new HttpStatusCode(207, "Multi-Status");
    public static HttpStatusCode ALREADY_REPORTED_208 = new HttpStatusCode(208, "Already Reported");
    public static HttpStatusCode IM_USED_226 = new HttpStatusCode(226, "IM Used");
    public static HttpStatusCode MULTIPLE_CHOICES_300 = new HttpStatusCode(300, "Multiple Choices");
    public static HttpStatusCode MOVED_PERMANENTLY_301 = new HttpStatusCode(301, "Moved Permanently");
    public static HttpStatusCode FOUND_302 = new HttpStatusCode(302, "Found");
    public static HttpStatusCode SEE_OTHER_303 = new HttpStatusCode(303, "See Other");
    public static HttpStatusCode NOT_MODIFIED_304 = new HttpStatusCode(304, "Not Modified");
    public static HttpStatusCode USE_PROXY_305 = new HttpStatusCode(305, "Use Proxy");
    public static HttpStatusCode TEMPORARY_REDIRECT_307 = new HttpStatusCode(307, "Temporary Redirect");
    public static HttpStatusCode PERMANENT_REDIRECT_308 = new HttpStatusCode(308, "Permanent Redirect");
    public static HttpStatusCode BAD_REQUEST_400 = new HttpStatusCode(400, "Bad Request");
    public static HttpStatusCode UNAUTHORIZED_401 = new HttpStatusCode(401, "Unauthorized");
    public static HttpStatusCode PAYMENT_REQUIRED_402 = new HttpStatusCode(402, "Payment Required");
    public static HttpStatusCode FORBIDDEN_403 = new HttpStatusCode(403, "Forbidden");
    public static HttpStatusCode NOT_FOUND_404 = new HttpStatusCode(404, "Not Found");
    public static HttpStatusCode METHOD_NOT_ALLOWED_405 = new HttpStatusCode(405, "Method Not Allowed");
    public static HttpStatusCode NOT_ACCEPTABLE_406 = new HttpStatusCode(406, "Not Acceptable");
    public static HttpStatusCode PROXY_AUTHENTICATION_REQUIRED_407 = new HttpStatusCode(407, "Proxy Authentication Required");
    public static HttpStatusCode REQUEST_TIMEOUT_408 = new HttpStatusCode(408, "Request Timeout");
    public static HttpStatusCode CONFLICT_409 = new HttpStatusCode(409, "Conflict");
    public static HttpStatusCode GONE_410 = new HttpStatusCode(410, "Gone");
    public static HttpStatusCode LENGTH_REQUIRED_411 = new HttpStatusCode(411, "Length Required");
    public static HttpStatusCode PRECONDITION_FAILED_412 = new HttpStatusCode(412, "Precondition Failed");
    public static HttpStatusCode CONTENT_TOO_LARGE_413 = new HttpStatusCode(413, "Content Too Large");
    public static HttpStatusCode URI_TOO_LONG_414 = new HttpStatusCode(414, "URI Too Long");
    public static HttpStatusCode UNSUPPORTED_MEDIA_TYPE_415 = new HttpStatusCode(415, "Unsupported Media Type");
    public static HttpStatusCode RANGE_NOT_SATISFIABLE_416 = new HttpStatusCode(416, "Range Not Satisfiable");
    public static HttpStatusCode EXPECTATION_FAILED_417 = new HttpStatusCode(417, "Expectation Failed");
    public static HttpStatusCode MISDIRECTED_REQUEST_421 = new HttpStatusCode(421, "Misdirected Request");
    public static HttpStatusCode UNPROCESSABLE_CONTENT_422 = new HttpStatusCode(422, "Unprocessable Content");
    public static HttpStatusCode LOCKED_423 = new HttpStatusCode(423, "Locked");
    public static HttpStatusCode FAILED_DEPENDENCY_424 = new HttpStatusCode(424, "Failed Dependency");
    public static HttpStatusCode TOO_EARLY_425 = new HttpStatusCode(425, "Too Early");
    public static HttpStatusCode UPGRADE_REQUIRED_426 = new HttpStatusCode(426, "Upgrade Required");
    public static HttpStatusCode PRECONDITION_REQUIRED_428 = new HttpStatusCode(428, "Precondition Required");
    public static HttpStatusCode TOO_MANY_REQUESTS_429 = new HttpStatusCode(429, "Too Many Requests");
    public static HttpStatusCode REQUEST_HEADER_FIELDS_TOO_LARGE_431 = new HttpStatusCode(431, "Request Header Fields Too Large");
    public static HttpStatusCode UNAVAILABLE_FOR_LEGAL_REASONS_451 = new HttpStatusCode(451, "Unavailable For Legal Reasons");
    public static HttpStatusCode INTERNAL_SERVER_ERROR_500 = new HttpStatusCode(500, "Internal Server Error");
    public static HttpStatusCode NOT_IMPLEMENTED_501 = new HttpStatusCode(501, "Not Implemented");
    public static HttpStatusCode BAD_GATEWAY_502 = new HttpStatusCode(502, "Bad Gateway");
    public static HttpStatusCode SERVICE_UNAVAILABLE_503 = new HttpStatusCode(503, "Service Unavailable");
    public static HttpStatusCode GATEWAY_TIMEOUT_504 = new HttpStatusCode(504, "Gateway Timeout");
    public static HttpStatusCode HTTP_VERSION_NOT_SUPPORTED_505 = new HttpStatusCode(505, "HTTP Version Not Supported");
    public static HttpStatusCode VARIANT_ALSO_NEGOTIATES_506 = new HttpStatusCode(506, "Variant Also Negotiates");
    public static HttpStatusCode INSUFFICIENT_STORAGE_507 = new HttpStatusCode(507, "Insufficient Storage");
    public static HttpStatusCode LOOP_DETECTED_508 = new HttpStatusCode(508, "Loop Detected");
    public static HttpStatusCode NETWORK_AUTHENTICATION_REQUIRED_511 = new HttpStatusCode(511, "Network Authentication Required");


    private static final Map<Integer, HttpStatusCode> predefined;
    static {
        var map = new HashMap<Integer, HttpStatusCode>();
        for (Field declaredField : HttpStatusCode.class.getDeclaredFields()) {
            if (declaredField.getType().equals(HttpStatusCode.class)) {
                try {
                    HttpStatusCode code = (HttpStatusCode) declaredField.get(null);
                    map.put(code.code, code);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        predefined = map;
    }

}
