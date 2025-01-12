package io.muserver;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An HTTP response code
 */
public class HttpStatus {

    private final int code;
    private final String reason;
    private byte@Nullable[] responseLineBytes;

    HttpStatus(int code, String reason) {
        if (code < 100 || code > 999) throw new IllegalArgumentException("Status codes must be 3 digits");
        this.code = code;
        this.reason = reason;
    }

    /**
     * @return the 3 digit status code, for example <code>200</code>
     */
    public int code() {
        return code;
    }

    /**
     * @return a description of the status, e.g. for <code>200</code> the reason phrase is <code>OK</code>
     */
    public String reasonPhrase() {
        return reason;
    }

    /**
     * @return True if the code is between 100 and 199
     */
    public boolean isInformational() {
        return code / 100 == 1;
    }
    /**
     * @return True if the code is between 200 and 299
     */
    public boolean isSuccessful() {
        return code / 100 == 2;
    }
    /**
     * @return True if the code is between 300 and 399
     */
    public boolean isRedirection() {
        return code / 100 == 3;
    }
    /**
     * @return True if the code is between 400 and 499
     */
    public boolean isClientError() {
        return code / 100 == 4;
    }
    /**
     * @return True if the code is between 500 and 599
     */
    public boolean isServerError() {
        return code / 100 == 5;
    }

    /**
     * @return An HTTP1 response line, as ascii bytes, e.g. <code>HTTP/1.1 200 OK\r\n</code>
     */
    byte[] http11ResponseLine() {
        // Not strictly thread safe, but it doesn't matter if multiple threads overwrite each other
        byte[] cached = this.responseLineBytes;
        if (cached != null) return cached;
        byte[] bytes = (HttpVersion.HTTP_1_1.version() + " " + code + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII);
        this.responseLineBytes = bytes;
        return bytes;
    }

    boolean noContentLengthHeader() {
        return isInformational() || code == 204 || code == 304 || code == 205;
    }
    boolean canHaveContent() {
        return !noContentLengthHeader();
    }

    /**
     * @param other A status code to compare
     * @return <code>true</code> if the code of the two status codes match (even if the reason phrases are different)
     * @throws IllegalArgumentException if <code>other</code> is null
     */
    public boolean sameCode(HttpStatus other) {
        Mutils.notNull("other", other);
        return other.code == this.code;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttpStatus that = (HttpStatus) o;
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

    /**
     * Gets or creates a status code with the given code
     * <p>Custom response codes can be created with this. For predefined codes, consider using the the static
     * fields in this class, for example {@link #OK_200}.</p>
     * @param code The status code to return
     * @return A status code object
     * @throws IllegalArgumentException code is less than 100 or greater than 999
     */
    public static HttpStatus of(int code) {
        var c = predefined.get(code);
        return c != null ? c : new HttpStatus(code, "Unspecified");
    }

    /**
     * Gets or creates a status code with the given code and reason phrase
     * <p>Custom response codes can be created with this. For predefined codes, consider using the the static
     * fields in this class, for example {@link #OK_200}.</p>
     * @param code The status code to return
     * @param reason The reason phrase
     * @return A status code object
     * @throws IllegalArgumentException code is less than 100 or greater than 999, or the reason phrase is null,
     * blank, or contains illegal characters
     */
    public static HttpStatus of(int code, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("No reason code given");
        if (!reason.matches("[A-Za-z0-9 -_]+")) throw new IllegalArgumentException("Invalid reason phrase");
        var c = predefined.get(code);
        if (c != null && c.reason.equals(reason)) return c;
        return new HttpStatus(code, reason);
    }

    /**
     * <code>HTTP 100 Continue</code>
     * <p>The 100 status code indicates that the initial part of the request has been received and has not yet been rejected by the server.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/100">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/100</a>
     */
    public static HttpStatus CONTINUE_100 = new HttpStatus(100, "Continue");

    /**
     * <code>HTTP 101 Switching Protocols</code>
     * <p>The 101 status code indicates that the server understands and is willing to comply with the client's request to switch protocols.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/101">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/101</a>
     */
    public static HttpStatus SWITCHING_PROTOCOLS_101 = new HttpStatus(101, "Switching Protocols");

    /**
     * <code>HTTP 102 Processing</code>
     * <p>The 102 status code is an interim response indicating that the server has received and is processing the client's request, but no response is available yet.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/102">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/102</a>
     */
    public static HttpStatus PROCESSING_102 = new HttpStatus(102, "Processing");

    /**
     * <code>HTTP 103 Early Hints</code>
     * <p>The 103 status code is primarily intended to be used with the Link header to allow the user agent to start preloading resources while the server is still preparing a response.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/103">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/103</a>
     */
    public static HttpStatus EARLY_HINTS_103 = new HttpStatus(103, "Early Hints");

    /**
     * <code>HTTP 200 OK</code>
     * <p>The 200 status code indicates that the request has succeeded. The meaning of the success depends on the HTTP method used:</p>
     * <ul>
     *   <li>GET: The resource has been fetched and is transmitted in the message body.</li>
     *   <li>HEAD: The representation headers are included in the response without any message body.</li>
     *   <li>POST: The resource describing the result of the action is transmitted in the message body.</li>
     *   <li>PUT or PATCH: The resource describing the result of the action is transmitted in the message body.</li>
     *   <li>DELETE: The resource describing the result of the action is transmitted in the message body.</li>
     * </ul>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/200">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/200</a>
     */
    public static HttpStatus OK_200 = new HttpStatus(200, "OK");

    /**
     * <code>HTTP 201 Created</code>
     * <p>The 201 status code indicates that the request has been fulfilled and has resulted in one or more new resources being created.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/201">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/201</a>
     */
    public static HttpStatus CREATED_201 = new HttpStatus(201, "Created");

    /**
     * <code>HTTP 202 Accepted</code>
     * <p>The 202 status code indicates that the request has been accepted for processing, but the processing has not been completed.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/202">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/202</a>
     */
    public static HttpStatus ACCEPTED_202 = new HttpStatus(202, "Accepted");

    /**
     * <code>HTTP 203 Non-Authoritative Information</code>
     * <p>The 203 status code is a non-authoritative informational response indicating that the client's request was successful, but the information returned is from a source that may be different from the original server.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/203">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/203</a>
     */
    public static HttpStatus NON_AUTHORITATIVE_INFORMATION_203 = new HttpStatus(203, "Non-Authoritative Information");

    /**
     * <code>HTTP 204 No Content</code>
     * <p>The 204 status code indicates that the server has successfully fulfilled the request, but there is no need to return an entity-body, and there is nothing to update in the client's cached representation.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/204">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/204</a>
     */
    public static HttpStatus NO_CONTENT_204 = new HttpStatus(204, "No Content");

    /**
     * <code>HTTP 205 Reset Content</code>
     * <p>The 205 status code indicates that the server has fulfilled the request, and the user agent should reset the document view that caused the request to be sent.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/205">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/205</a>
     */
    public static HttpStatus RESET_CONTENT_205 = new HttpStatus(205, "Reset Content");

    /**
     * <code>HTTP 206 Partial Content</code>
     * <p>The 206 status code indicates that the server has fulfilled the partial GET request for the resource, and the response is a representation of the result of one or more instance-manipulations applied to the current instance.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/206">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/206</a>
     */
    public static HttpStatus PARTIAL_CONTENT_206 = new HttpStatus(206, "Partial Content");

    /**
     * <code>HTTP 207 Multi-Status</code>
     * <p>The 207 status code indicates that the message returned is a multiple status response, as for a WebDAV PROPFIND or PROPPATCH request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/207">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/207</a>
     */
    public static HttpStatus MULTI_STATUS_207 = new HttpStatus(207, "Multi-Status");

    /**
     * <code>HTTP 208 Already Reported</code>
     * <p>The 208 status code indicates that the members of a DAV binding have already been enumerated in a preceding part of the (multistatus) response, and are not being included again.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/208">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/208</a>
     */
    public static HttpStatus ALREADY_REPORTED_208 = new HttpStatus(208, "Already Reported");

    /**
     * <code>HTTP 226 IM Used</code>
     * <p>The 226 status code indicates that the server has fulfilled a request for the resource, and the response is a representation of the result of one or more instance-manipulations applied to the current instance.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/226">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/226</a>
     */
    public static HttpStatus IM_USED_226 = new HttpStatus(226, "IM Used");

    /**
     * <code>HTTP 300 Multiple Choices</code>
     * <p>The 300 status code indicates that the requested resource corresponds to any one of a set of representations, each with its own specific location, and agent-driven negotiation information is being provided so that the user (or user agent) can select a preferred representation and redirect its request to that location.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/300">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/300</a>
     */
    public static HttpStatus MULTIPLE_CHOICES_300 = new HttpStatus(300, "Multiple Choices");

    /**
     * <code>HTTP 301 Moved Permanently</code>
     * <p>The 301 status code indicates that the requested resource has been assigned a new permanent URI, and any future references to this resource should use one of the enclosed URIs.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/301">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/301</a>
     */
    public static HttpStatus MOVED_PERMANENTLY_301 = new HttpStatus(301, "Moved Permanently");

    /**
     * <code>HTTP 302 Found</code>
     * <p>The 302 status code indicates that the requested resource resides temporarily under a different URI. The temporary URI should be given in the Location field in the response.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/302</a>
     */
    public static HttpStatus FOUND_302 = new HttpStatus(302, "Found");

    /**
     * <code>HTTP 303 See Other</code>
     * <p>The 303 status code indicates that the response to the request can be found under a different URI and should be retrieved using a GET method on that resource.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/303">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/303</a>
     */
    public static HttpStatus SEE_OTHER_303 = new HttpStatus(303, "See Other");

    /**
     * <code>HTTP 304 Not Modified</code>
     * <p>The 304 status code indicates that a conditional GET request has been received and would have resulted in a 200 OK response if it were not for the fact that the condition evaluated to false.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/304">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/304</a>
     */
    public static HttpStatus NOT_MODIFIED_304 = new HttpStatus(304, "Not Modified");

    /**
     * <code>HTTP 305 Use Proxy</code>
     * <p>The 305 status code indicates that the requested resource must be accessed through the proxy given by the Location field.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/305">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/305</a>
     */
    public static HttpStatus USE_PROXY_305 = new HttpStatus(305, "Use Proxy");

    /**
     * <code>HTTP 307 Temporary Redirect</code>
     * <p>The 307 status code indicates that the request should be repeated with another URI; however, future requests should still use the original URI.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/307">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/307</a>
     */
    public static HttpStatus TEMPORARY_REDIRECT_307 = new HttpStatus(307, "Temporary Redirect");

    /**
     * <code>HTTP 308 Permanent Redirect</code>
     * <p>The 308 status code indicates that the request and all future requests should be repeated using another URI. The new URI is given in the Location field.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/308">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/308</a>
     */
    public static HttpStatus PERMANENT_REDIRECT_308 = new HttpStatus(308, "Permanent Redirect");

    /**
     * <code>HTTP 400 Bad Request</code>
     * <p>The 400 status code indicates that the server cannot or will not process the request due to something that is perceived to be a client error.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/400">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/400</a>
     */
    public static HttpStatus BAD_REQUEST_400 = new HttpStatus(400, "Bad Request");

    /**
     * <code>HTTP 401 Unauthorized</code>
     * <p>The 401 status code indicates that the request has not been applied because it lacks valid authentication credentials for the target resource.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/401">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/401</a>
     */
    public static HttpStatus UNAUTHORIZED_401 = new HttpStatus(401, "Unauthorized");

    /**
     * <code>HTTP 402 Payment Required</code>
     * <p>The 402 status code is reserved for future use and is intended to represent digital cash or other forms of digital payment.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/402">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/402</a>
     */
    public static HttpStatus PAYMENT_REQUIRED_402 = new HttpStatus(402, "Payment Required");

    /**
     * <code>HTTP 403 Forbidden</code>
     * <p>The 403 status code indicates that the server understood the request, but it refuses to fulfill it or is incapable of doing so.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/403">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/403</a>
     */
    public static HttpStatus FORBIDDEN_403 = new HttpStatus(403, "Forbidden");

    /**
     * <code>HTTP 404 Not Found</code>
     * <p>The 404 status code indicates that the server cannot find the requested resource. This status is often used in response to a GET request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/404">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/404</a>
     */
    public static HttpStatus NOT_FOUND_404 = new HttpStatus(404, "Not Found");

    /**
     * <code>HTTP 405 Method Not Allowed</code>
     * <p>The 405 status code indicates that the method specified in the request is not allowed for the resource identified by the request URI.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/405">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/405</a>
     */
    public static HttpStatus METHOD_NOT_ALLOWED_405 = new HttpStatus(405, "Method Not Allowed");

    /**
     * <code>HTTP 406 Not Acceptable</code>
     * <p>The 406 status code indicates that the server cannot produce a response matching the list of acceptable values defined in the request's headers.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/406">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/406</a>
     */
    public static HttpStatus NOT_ACCEPTABLE_406 = new HttpStatus(406, "Not Acceptable");

    /**
     * <code>HTTP 407 Proxy Authentication Required</code>
     * <p>The 407 status code is similar to 401 (Unauthorized), but it indicates that the client must first authenticate itself with the proxy.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/407">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/407</a>
     */
    public static HttpStatus PROXY_AUTHENTICATION_REQUIRED_407 = new HttpStatus(407, "Proxy Authentication Required");

    /**
     * <code>HTTP 408 Request Timeout</code>
     * <p>The 408 status code indicates that the server did not receive a complete request message within the time that it was prepared to wait.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/408">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/408</a>
     */
    public static HttpStatus REQUEST_TIMEOUT_408 = new HttpStatus(408, "Request Timeout");

    /**
     * <code>HTTP 409 Conflict</code>
     * <p>The 409 status code indicates that the request could not be completed due to a conflict with the current state of the target resource.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/409">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/409</a>
     */
    public static HttpStatus CONFLICT_409 = new HttpStatus(409, "Conflict");

    /**
     * <code>HTTP 410 Gone</code>
     * <p>The 410 status code indicates that the requested resource is no longer available at the server and no forwarding address is known.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/410">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/410</a>
     */
    public static HttpStatus GONE_410 = new HttpStatus(410, "Gone");

    /**
     * <code>HTTP 411 Length Required</code>
     * <p>The 411 status code indicates that the server refuses to accept the request without a defined Content-Length.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/411">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/411</a>
     */
    public static HttpStatus LENGTH_REQUIRED_411 = new HttpStatus(411, "Length Required");

    /**
     * <code>HTTP 412 Precondition Failed</code>
     * <p>The 412 status code indicates that one or more preconditions given in the request header fields evaluated to false when tested on the server.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/412">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/412</a>
     */
    public static HttpStatus PRECONDITION_FAILED_412 = new HttpStatus(412, "Precondition Failed");

    /**
     * <code>HTTP 413 Content Too Large</code>
     * <p>The 413 status code indicates that the request is larger than the server is willing or able to process. The server MAY close the connection to prevent the client from continuing the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/413">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/413</a>
     */
    public static HttpStatus CONTENT_TOO_LARGE_413 = new HttpStatus(413, "Content Too Large");

    /**
     * <code>HTTP 414 URI Too Long</code>
     * <p>The 414 status code indicates that the URI provided in the request is longer than the server is willing to interpret.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/414">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/414</a>
     */
    public static HttpStatus URI_TOO_LONG_414 = new HttpStatus(414, "URI Too Long");

    /**
     * <code>HTTP 415 Unsupported Media Type</code>
     * <p>The 415 status code indicates that the server refuses to accept the request because the payload format is in an unsupported format.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/415">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/415</a>
     */
    public static HttpStatus UNSUPPORTED_MEDIA_TYPE_415 = new HttpStatus(415, "Unsupported Media Type");

    /**
     * <code>HTTP 416 Range Not Satisfiable</code>
     * <p>The 416 status code indicates that the client has asked for a portion of the file (byte serving), but the server cannot supply that portion.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/416">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/416</a>
     */
    public static HttpStatus RANGE_NOT_SATISFIABLE_416 = new HttpStatus(416, "Range Not Satisfiable");

    /**
     * <code>HTTP 417 Expectation Failed</code>
     * <p>The 417 status code indicates that the expectation given in the request's Expect header field could not be met by at least one of the inbound servers.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/417">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/417</a>
     */
    public static HttpStatus EXPECTATION_FAILED_417 = new HttpStatus(417, "Expectation Failed");

    /**
     * <code>HTTP 421 Misdirected Request</code>
     * <p>The 421 status code indicates that the request was directed at a server that is not able to produce a response.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/421">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/421</a>
     */
    public static HttpStatus MISDIRECTED_REQUEST_421 = new HttpStatus(421, "Misdirected Request");

    /**
     * <code>HTTP 422 Unprocessable Content</code>
     * <p>The 422 status code indicates that the server understands the content type of the request entity, and the syntax of the request entity is correct, but it was unable to process the contained instructions.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/422">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/422</a>
     */
    public static HttpStatus UNPROCESSABLE_CONTENT_422 = new HttpStatus(422, "Unprocessable Content");

    /**
     * <code>HTTP 423 Locked</code>
     * <p>The 423 status code indicates that the source or destination resource of a method is locked.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/423">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/423</a>
     */
    public static HttpStatus LOCKED_423 = new HttpStatus(423, "Locked");

    /**
     * <code>HTTP 424 Failed Dependency</code>
     * <p>The 424 status code indicates that the method could not be performed on the resource because the requested action depended on another action and that action failed.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/424">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/424</a>
     */
    public static HttpStatus FAILED_DEPENDENCY_424 = new HttpStatus(424, "Failed Dependency");

    /**
     * <code>HTTP 425 Too Early</code>
     * <p>The 425 status code indicates that the server is unwilling to risk processing a request that might be replayed.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/425">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/425</a>
     */
    public static HttpStatus TOO_EARLY_425 = new HttpStatus(425, "Too Early");

    /**
     * <code>HTTP 426 Upgrade Required</code>
     * <p>The 426 status code indicates that the server refuses to perform the request using the current protocol but might be willing to do so after the client upgrades to a different protocol.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/426">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/426</a>
     */
    public static HttpStatus UPGRADE_REQUIRED_426 = new HttpStatus(426, "Upgrade Required");

    /**
     * <code>HTTP 428 Precondition Required</code>
     * <p>The 428 status code indicates that the origin server requires the request to be conditional.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/428">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/428</a>
     */
    public static HttpStatus PRECONDITION_REQUIRED_428 = new HttpStatus(428, "Precondition Required");

    /**
     * <code>HTTP 429 Too Many Requests</code>
     * <p>The 429 status code indicates that the user has sent too many requests in a given amount of time ("rate limiting").</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/429">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/429</a>
     */
    public static HttpStatus TOO_MANY_REQUESTS_429 = new HttpStatus(429, "Too Many Requests");

    /**
     * <code>HTTP 431 Request Header Fields Too Large</code>
     * <p>The 431 status code indicates that the server is unwilling to process the request because its header fields are too large.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/431">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/431</a>
     */
    public static HttpStatus REQUEST_HEADER_FIELDS_TOO_LARGE_431 = new HttpStatus(431, "Request Header Fields Too Large");

    /**
     * <code>HTTP 451 Unavailable For Legal Reasons</code>
     * <p>The 451 status code indicates that the server is denying access to the resource as a response to a legal demand.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/451">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/451</a>
     */
    public static HttpStatus UNAVAILABLE_FOR_LEGAL_REASONS_451 = new HttpStatus(451, "Unavailable For Legal Reasons");

    /**
     * <code>HTTP 500 Internal Server Error</code>
     * <p>The 500 status code indicates that the server encountered an unexpected condition that prevented it from fulfilling the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/500">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/500</a>
     */
    public static HttpStatus INTERNAL_SERVER_ERROR_500 = new HttpStatus(500, "Internal Server Error");

    /**
     * <code>HTTP 501 Not Implemented</code>
     * <p>The 501 status code indicates that the server does not support the functionality required to fulfill the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/501">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/501</a>
     */
    public static HttpStatus NOT_IMPLEMENTED_501 = new HttpStatus(501, "Not Implemented");

    /**
     * <code>HTTP 502 Bad Gateway</code>
     * <p>The 502 status code indicates that the server, while acting as a gateway or proxy, received an invalid response from the upstream server it accessed in attempting to fulfill the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/502">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/502</a>
     */
    public static HttpStatus BAD_GATEWAY_502 = new HttpStatus(502, "Bad Gateway");

    /**
     * <code>HTTP 503 Service Unavailable</code>
     * <p>The 503 status code indicates that the server is currently unable to handle the request due to temporary overloading or maintenance of the server.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/503">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/503</a>
     */
    public static HttpStatus SERVICE_UNAVAILABLE_503 = new HttpStatus(503, "Service Unavailable");

    /**
     * <code>HTTP 504 Gateway Timeout</code>
     * <p>The 504 status code indicates that the server, while acting as a gateway or proxy, did not receive a timely response from the upstream server or application it needed to access in order to complete the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/504">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/504</a>
     */
    public static HttpStatus GATEWAY_TIMEOUT_504 = new HttpStatus(504, "Gateway Timeout");

    /**
     * <code>HTTP 505 HTTP Version Not Supported</code>
     * <p>The 505 status code indicates that the server does not support, or refuses to support, the major version of HTTP that was used in the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/505">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/505</a>
     */
    public static HttpStatus HTTP_VERSION_NOT_SUPPORTED_505 = new HttpStatus(505, "HTTP Version Not Supported");

    /**
     * <code>HTTP 506 Variant Also Negotiates</code>
     * <p>The 506 status code indicates that the server has an internal configuration error: transparent content negotiation for the request results in a circular reference.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/506">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/506</a>
     */
    public static HttpStatus VARIANT_ALSO_NEGOTIATES_506 = new HttpStatus(506, "Variant Also Negotiates");

    /**
     * <code>HTTP 507 Insufficient Storage</code>
     * <p>The 507 status code indicates that the server is unable to store the representation needed to complete the request.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/507">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/507</a>
     */
    public static HttpStatus INSUFFICIENT_STORAGE_507 = new HttpStatus(507, "Insufficient Storage");

    /**
     * <code>HTTP 508 Loop Detected</code>
     * <p>The 508 status code indicates that the server has detected an infinite loop while processing a request with "WebDAV: Loop Detected".</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/508">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/508</a>
     */
    public static HttpStatus LOOP_DETECTED_508 = new HttpStatus(508, "Loop Detected");

    /**
     * <code>HTTP 511 Network Authentication Required</code>
     * <p>The 511 status code indicates that the client needs to authenticate to gain network access.</p>
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/511">https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/511</a>
     */
    public static HttpStatus NETWORK_AUTHENTICATION_REQUIRED_511 = new HttpStatus(511, "Network Authentication Required");




    private static final Map<Integer, HttpStatus> predefined;
    static {
        var map = new HashMap<Integer, HttpStatus>();
        for (Field declaredField : HttpStatus.class.getDeclaredFields()) {
            if (declaredField.getType().equals(HttpStatus.class)) {
                try {
                    HttpStatus code = (HttpStatus) declaredField.get(null);
                    map.put(code.code, code);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Error trying to preload " + declaredField, e);
                }
            }
        }
        predefined = map;
    }

}
