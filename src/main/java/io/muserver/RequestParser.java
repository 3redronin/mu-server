package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static io.muserver.HttpStatusCode.BAD_REQUEST_400;

class RequestParser {

    final int maxUrlLength;
    final int maxHeaderSize;
    private State state = State.RL_METHOD;
    private final StringBuffer cur = new StringBuffer();
    private Method method;
    private URI requestUri;
    private HttpVersion protocol;
    private MuHeaders headers = new MuHeaders();
    private MuHeaders trailers;
    private String curHeader;
    private List<String> curVals;
    private long bodyLength = -1; // -2 is chunked
    private long bodyBytesRead;
    private ChunkState chunkState;
    private long curChunkSize = -1;
    private int headerSize = 0;


    private void reset() {
        state = State.RL_METHOD;
        cur.setLength(0);
        method = null;
        requestUri = null;
        protocol = null;
        headers = new MuHeaders();
        trailers = null;
        curHeader = null;
        curVals = null;
        bodyLength = -1;
        bodyBytesRead = 0;
        chunkState = null;
        curChunkSize = -1;
        headerSize = 0;
    }

    RequestParser(int maxUrlLength, int maxHeaderSize) {
        this.maxUrlLength = maxUrlLength;
        this.maxHeaderSize = maxHeaderSize;
    }

    Method method() {
        return method;
    }

    public boolean requestBodyExpectedNext() {
        return state == State.FIXED_BODY || state == State.CHUNKED_BODY;
    }

    private enum State {
        RL_METHOD, RL_URI, RL_PROTO, H_NAME, H_VALUE, FIXED_BODY, CHUNKED_BODY, COMPLETE
    }

    private enum ChunkState {
        SIZE, EXTENSION, DATA, DATA_DONE, TRAILER_NAME, TRAILER_VALUE
    }

    private static final Logger log = LoggerFactory.getLogger(RequestParser.class);

    ConMessage offer(ByteBuffer bb) throws InvalidRequestException, RedirectException {

        if (state == State.RL_METHOD) {
            String s = new String(bb.array(), bb.position(), bb.limit());
            int maxLengthToLog = 400;
            if (s.length() > maxLengthToLog) {
                log.info("<<\n" + s.substring(0, maxLengthToLog).replace("\r", "\\r").replace("\n", "\\n\r\n") + "...(" + s.length() + " total)");
            } else {
                log.info("<<\n" + s.replace("\r", "\\r").replace("\n", "\\n\r\n"));
            }
        } else {
            log.info("<<\n<request body> " + bb.remaining() + " bytes");
        }

        while (bb.hasRemaining()) {
            ConMessage msg;
            if (state == State.FIXED_BODY) {
                msg = parseFixedLengthBody(bb);
            } else if (state == State.CHUNKED_BODY) {
                msg = parseChunkedBody(bb);
            } else {
                msg = parseReqLineAndHeaders(bb);
            }
            if (msg != null) {
                return msg;
            }
        }
        return null;
    }

    private ConMessage parseReqLineAndHeaders(ByteBuffer bb) throws InvalidRequestException, RedirectException {
        if (state == State.COMPLETE) reset();
        while (bb.hasRemaining()) {

            byte c = bb.get();

            headerSize++;
            if (headerSize > maxHeaderSize) {
                throw new InvalidRequestException(HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE_431, null, "Header length (including all white space) reached " + headerSize + " bytes.");
            }

            if (c == '\r') {
                continue; // as per spec, \r can be ignored in line endings when parsing
            }

            if (state == State.RL_METHOD) {
                if (c == ' ') {
                    try {
                        method = Method.valueOf(cur.toString());
                    } catch (IllegalArgumentException e) {
                        throw new InvalidRequestException(HttpStatusCode.METHOD_NOT_ALLOWED_405, "Invalid HTTP method in the request", "The HTTP method " + cur + " is not supported by mu-server");
                    }
                    state = State.RL_URI;
                    cur.setLength(0);
                } else if (Parser.isTChar(c)) {
                    append(c);
                } else {
                    throw new InvalidRequestException(BAD_REQUEST_400, "Invalid character in request line", "Got a " + c + " character in the request line");
                }
            } else if (state == State.RL_URI) {
                if (c == ' ') {
                    String uriStr = cur.toString();
                    if (uriStr.startsWith(".")) {
                        throw new InvalidRequestException(BAD_REQUEST_400, "Invalid URI", "The given URI started with a '.': " + uriStr);
                    }
                    try {
                        requestUri = new URI(uriStr).normalize();
                        if (requestUri.getPath() == null) {
                            requestUri = requestUri.resolve("/");
                        }
                    } catch (URISyntaxException e) {
                        throw new InvalidRequestException(BAD_REQUEST_400, "Invalid URI", "URI parsing failed: " + e.getMessage());
                    }
                    if (requestUri.getPath().startsWith("/..")) {
                        throw new InvalidRequestException(BAD_REQUEST_400, "Invalid URI", "The URI had '..' after normalisation. Raw value was " + uriStr);
                    }
                    state = State.RL_PROTO;
                    cur.setLength(0);
                } else {
                    append(c);
                    if (cur.length() > maxUrlLength) {
                        throw new InvalidRequestException(HttpStatusCode.URI_TOO_LONG_414, "Please use a shorter request URL", "The URL " + cur + " exceeded the maximum URL length allowed, which is " + maxUrlLength);
                    }
                }
            } else if (state == State.RL_PROTO) {
                if (c == '\n') {
                    this.protocol = HttpVersion.fromVersion(cur.toString());
                    if (this.protocol == null || this.protocol == HttpVersion.HTTP_1_0) { // TODO bother supporting 1.0?
                        throw new InvalidRequestException(HttpStatusCode.HTTP_VERSION_NOT_SUPPORTED_505, "HTTP Version Not Supported", "Http version was " + protocol);
                    }
                    this.state = State.H_NAME;
                    cur.setLength(0);
                } else {
                    append(c);
                }

            } else if (state == State.H_NAME) {

                if (c == ' ') {
                    throw new InvalidRequestException(BAD_REQUEST_400, "HTTP protocol error: space in header name", "Shouldn't have a space while in " + state);
                } else if (c == '\n') {
                    if (cur.length() > 0) {
                        throw new InvalidRequestException(BAD_REQUEST_400, "A header name included a line feed character", "Value was " + cur);
                    }
                    cur.setLength(0);

                    boolean hasContentLength = bodyLength > -1;
                    boolean hasTransferEncoding = headers.contains("transfer-encoding");
                    if (hasContentLength || hasTransferEncoding) {
                        if (hasContentLength && hasTransferEncoding) {
                            throw new InvalidRequestException(BAD_REQUEST_400, "A request cannot have both transfer encoding and content length", "Headers were " + headers);
                        }
                        if (hasContentLength) {
                            if (bodyLength == 0) {
                                state = State.COMPLETE;
                            } else {
                                state = State.FIXED_BODY;
                            }
                        } else {
                            chunkState = ChunkState.SIZE;
                            state = State.CHUNKED_BODY;
                        }
                    } else {
                        state = State.COMPLETE;
                    }

                    if (!headers.contains(HeaderNames.HOST)) {
                        throw new InvalidRequestException(BAD_REQUEST_400, "No host header specified", "No host header specified");
                    }

                    return new NewRequest(protocol, method, requestUri, getRelativeUrl(requestUri), headers, state != State.COMPLETE);
                } else if (c == ':') {

                    String header = cur.toString();
                    this.curHeader = header;
                    if (headers.contains(header)) {
                        curVals = headers.getAll(header);
                    } else {
                        curVals = new ArrayList<>();
                        headers.set(header, curVals);
                    }
                    state = State.H_VALUE;
                    cur.setLength(0);
                } else {
                    append(c);
                }

            } else if (state == State.H_VALUE) {

                if (c == ' ') {
                    if (cur.length() > 0) {
                        append(c);
                    } // else ignore pre-pended space on a header value

                } else if (c == '\n') {

                    String val = cur.toString().trim();
                    switch (curHeader.toLowerCase()) {
                        case "content-length":
                            if (bodyLength == -2) {
                                throw new InvalidRequestException(BAD_REQUEST_400, "Content-Length set after chunked encoding sent", "Headers were " + headers);
                            }
                            long prev = this.bodyLength;
                            try {
                                this.bodyLength = Long.parseLong(val);
                            } catch (NumberFormatException e) {
                                throw new InvalidRequestException(BAD_REQUEST_400, "Invalid content-length header", "Header was " + cur);
                            }
                            if (prev != -1 && prev != this.bodyLength) {
                                throw new InvalidRequestException(BAD_REQUEST_400, "Multiple content-length headers", "First was " + prev + " and then " + bodyLength);
                            }
                            break;
                        case "transfer-encoding":
                            if (bodyLength > -1) {
                                throw new InvalidRequestException(BAD_REQUEST_400, "Can't have transfer-encoding with content-length", "Headers were " + headers);
                            }
                            if (val.toLowerCase().endsWith("chunked")) {
                                this.bodyLength = -2;
                            }
                            break;
                    }
                    curVals.add(val);
                    cur.setLength(0);
                    state = State.H_NAME;
                } else {
                    append(c);
                }


            } else {
                throw new IllegalStateException("Should not be processing headers at state " + state);
            }
        }
        return null;
    }

    private ConMessage parseFixedLengthBody(ByteBuffer bb) {
        long expectedRemaining = bodyLength - bodyBytesRead;
        int toRead = (int)Math.min(expectedRemaining, bb.remaining());
        bodyBytesRead += toRead;
        var view = bb.slice(bb.position(), toRead);
        bb.position(bb.position() + toRead);
        boolean last = bodyBytesRead == bodyLength;
        if (last) state = State.COMPLETE;
        return new RequestBodyData(view, last);
    }

    private ConMessage parseChunkedBody(ByteBuffer bb) throws InvalidRequestException {
        if (chunkState != ChunkState.DATA) {
            while (bb.hasRemaining()) {
                byte c = bb.get();
                if (c == '\r') {
                    continue;
                }
                if (chunkState == ChunkState.TRAILER_NAME) {

                    if (c == '\n') {
                        if (cur.length() > 0) {
                            throw new InvalidRequestException(BAD_REQUEST_400, "HTTP Protocol error - trailer line had no value", "While reading a header name (" + cur + ") a newline was found, but there was no ':' first.");
                        }
                        if (trailers == null) {
                            trailers = MuHeaders.EMPTY;
                        }
                        state = State.COMPLETE;
                        return new EndOfChunks(trailers);
                    } else if (c == ':') {
                        String header = cur.toString();
                        this.curHeader = header;

                        if (trailers == null) {
                            trailers = new MuHeaders();
                        }
                        if (trailers.contains(header)) {
                            curVals = trailers.getAll(header);
                        } else {
                            curVals = new ArrayList<>();
                            trailers.set(header, curVals);
                        }
                        cur.setLength(0);
                        chunkState = ChunkState.TRAILER_VALUE;
                    } else {
                        append(c);
                    }
                } else if (chunkState == ChunkState.TRAILER_VALUE) {
                    if (c == '\n') {
                        String val = cur.toString().trim();
                        curVals.add(val);
                        cur.setLength(0);
                        chunkState = ChunkState.TRAILER_NAME;
                    } else {
                        append(c);
                    }
                } else if (chunkState == ChunkState.SIZE) {
                    if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                        append(c);
                    } else if (c == '\n' || c == ';') {
                        curChunkSize = Long.parseLong(cur.toString(), 16);
                        cur.setLength(0);
                        if (c == ';') {
                            chunkState = ChunkState.EXTENSION;
                        } else {
                            if (curChunkSize == 0) {
                                chunkState = ChunkState.TRAILER_NAME;
                            } else {
                                chunkState = ChunkState.DATA;
                                break; // break out of while loop parse body
                            }
                        }
                    } else {
                        throw new InvalidRequestException(BAD_REQUEST_400, "Invalid HTTP request body format", "Invalid character in chunk size declaration: " + c);
                    }
                } else if (chunkState == ChunkState.EXTENSION) {
                    if (c == '\n') {
                        if (curChunkSize == 0) {
                            chunkState = ChunkState.TRAILER_NAME;
                        } else {
                            chunkState = ChunkState.DATA;
                            break; // break out of while loop parse body
                        }
                    } // else ignore the character because chunked extensions are ignored by mu-server
                } else if (chunkState == ChunkState.DATA_DONE) {
                    if (c == '\n') {
                        chunkState = ChunkState.SIZE;
                    } else {
                        throw new InvalidRequestException(BAD_REQUEST_400, "Invalid HTTP request body format", "Extra data after chunk was supposed to end: " + c);
                    }
                } else {
                    throw new IllegalStateException("Unexpected state " + state);
                }
            }
        }

        if (chunkState == ChunkState.DATA && bb.hasRemaining()) {
            if (curChunkSize > 0) {
                int size = (int) Math.min(curChunkSize, bb.remaining());
                bodyBytesRead += size;
                curChunkSize -= size;
                var view = bb.slice(bb.position(), size);
                bb.position(bb.position() + size);
                return new RequestBodyData(view, false);
            } else {
                chunkState = ChunkState.DATA_DONE;
            }
        }
        return null;
    }

    private void append(byte c) {
        cur.append((char) c);
    }

    static String getRelativeUrl(URI uriInHeaderLine) throws InvalidRequestException, RedirectException {
        try {
            if (uriInHeaderLine.getScheme() == null && uriInHeaderLine.getHost() != null) {
                throw new RedirectException(new URI(uriInHeaderLine.toString().substring(1)).normalize());
            }

            String s = uriInHeaderLine.getRawPath();
            if (Mutils.nullOrEmpty(s)) {
                s = "/";
            } else {
                // TODO: consider a redirect if the URL is changed? Handle other percent-encoded characters?
                s = s.replace("%7E", "~")
                    .replace("%5F", "_")
                    .replace("%2E", ".")
                    .replace("%2D", "-")
                ;
            }
            String q = uriInHeaderLine.getRawQuery();
            if (q != null) {
                s += "?" + q;
            }
            return s;
        } catch (RedirectException re) {
            throw re;
        } catch (Exception e) {
            throw new InvalidRequestException(BAD_REQUEST_400, "invalid request URI", "Error while parsing URI '" + uriInHeaderLine + "': " + e.getMessage());
        }
    }

}


interface ConMessage {
}
record NewRequest(HttpVersion version, Method method, URI uri, String relativeUri, MuHeaders headers, boolean hasBody) implements ConMessage {}
record RequestBodyData(ByteBuffer buffer, boolean last) implements ConMessage {}
record EndOfChunks(MuHeaders trailers) implements ConMessage {}