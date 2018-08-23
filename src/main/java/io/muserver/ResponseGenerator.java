package io.muserver;

import javax.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class ResponseGenerator {
    private final HttpVersion httpVersion;

    public ResponseGenerator(HttpVersion httpVersion) {
        this.httpVersion = httpVersion;
    }

    public ByteBuffer writeHeader(int statusCode, MuHeaders headers) {
        Response.Status status = Response.Status.fromStatusCode(statusCode);
        String reason = status == null ? "Unknown" : status.getReasonPhrase();
        StringBuilder resp = new StringBuilder();
        resp.append(httpVersion.version()).append(' ').append(statusCode).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, List<String>> header : headers.all().entrySet()) {
            String name = header.getKey();
            for (String value : header.getValue()) {
                resp.append(name).append(": ").append(value).append("\r\n");
            }
        }
        resp.append("\r\n");
        return ByteBuffer.wrap(resp.toString().getBytes(StandardCharsets.US_ASCII));
    }
}
