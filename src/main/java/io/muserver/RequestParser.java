package io.muserver;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

class RequestParser {

    static class Options {
        final int maxUrlLength;
        final int maxHeaderSize;

        Options(int maxUrlLength, int maxHeaderSize) {
            this.maxHeaderSize = maxHeaderSize;
            if (maxUrlLength < 30) {
                throw new IllegalArgumentException("The Max URL length must be at least 30 characters, however " + maxUrlLength
                    + " was specified. It is recommended that a much larger value, such as 4096 is used to cater for URLs with long " +
                    "paths or many querystring parameters.");
            }
            this.maxUrlLength = maxUrlLength;
        }

        final static Options defaultOptions = new Options(16 * 1024, 16 * 1024);
    }

    private final Options options;
    private final RequestListener requestListener;

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

    RequestParser(Options options, RequestListener requestListener) {
        this.options = options;
        this.requestListener = requestListener;
    }

    private enum State {
        RL_METHOD, RL_URI, RL_PROTO, H_NAME, H_VALUE, FIXED_BODY, CHUNKED_BODY, COMPLETE
    }

    private enum ChunkState {
        SIZE, EXTENSION, DATA, DATA_DONE, TRAILER_NAME, TRAILER_VALUE
    }

    void offer(ByteBuffer bb) throws InvalidRequestException {
        while (bb.hasRemaining()) {
            if (state == State.FIXED_BODY) {
                parseFixedLengthBody(bb);
            } else if (state == State.CHUNKED_BODY) {
                parseChunkedBody(bb);
            } else {
                parseReqLineAndHeaders(bb);
            }
            maybeRaiseRequestComplete();
        }
    }

    void maybeRaiseRequestComplete() {
        if (state == State.COMPLETE) {
            requestListener.onRequestComplete(trailers);
            reset();
        }
    }

    private void parseReqLineAndHeaders(ByteBuffer bb) throws InvalidRequestException {
        while (bb.hasRemaining()) {

            byte c = bb.get();

            headerSize++;
            if (headerSize > options.maxHeaderSize) {
                throw new InvalidRequestException(431, "Request Header Fields Too Large", "Header length (including all white space) reached " + headerSize + " bytes.");
            }

            if (c == '\r') {
                continue; // as per spec, \r can be ignored in line endings when parsing
            }

            if (state == State.RL_METHOD) {
                if (c == ' ') {
                    try {
                        method = Method.valueOf(cur.toString());
                    } catch (IllegalArgumentException e) {
                        throw new InvalidRequestException(501, "Not Implemented", "The HTTP method " + cur + " is not supported by mu-server");
                    }
                    state = State.RL_URI;
                    cur.setLength(0);
                } else if (Parser.isTChar(c)) {
                    append(c);
                } else {
                    throw new InvalidRequestException(400, "Invalid character in request line", "Got a " + c + " character in the request line");
                }
            } else if (state == State.RL_URI) {
                if (c == ' ') {
                    String uriStr = cur.toString();
                    if (uriStr.charAt(0) != '/') {
                        throw new InvalidRequestException(400, "Bad Request - Invalid URI", "The URI did not start with a '/'. It was: " + uriStr);
                    }
                    requestUri = URI.create(uriStr).normalize();
                    if (requestUri.getPath().startsWith("/..")) {
                        throw new InvalidRequestException(400, "Bad Request - Invalid URI", "The URI had '..' after normalisation. Raw value was " + uriStr);
                    }
                    state = State.RL_PROTO;
                    cur.setLength(0);
                } else {
                    append(c);
                    if (cur.length() > options.maxUrlLength) {
                        throw new InvalidRequestException(414, "URI Too Long", "The URL " + cur + " exceeded the maximum URL length allowed, which is " + options.maxUrlLength);
                    }
                }
            } else if (state == State.RL_PROTO) {
                if (c == '\n') {
                    this.protocol = HttpVersion.fromRequestLine(cur.toString());
                    if (this.protocol == null || this.protocol == HttpVersion.HTTP_1_0) { // TODO bother supporting 1.0?
                        throw new InvalidRequestException(505, "HTTP Version Not Supported", "Http version was " + protocol);
                    }
                    this.state = State.H_NAME;
                    cur.setLength(0);
                } else {
                    append(c);
                }

            } else if (state == State.H_NAME) {

                if (c == ' ') {
                    throw new InvalidRequestException(400, "HTTP protocol error: space in header name", "Shouldn't have a space while in " + state);
                } else if (c == '\n') {
                    if (cur.length() > 0) {
                        throw new InvalidRequestException(400, "A header name included a line feed character", "Value was " + cur);
                    }
                    cur.setLength(0);

                    boolean hasContentLength = bodyLength > -1;
                    boolean hasTransferEncoding = headers.contains("transfer-encoding");
                    if (hasContentLength || hasTransferEncoding) {
                        if (hasContentLength && hasTransferEncoding) {
                            throw new InvalidRequestException(400, "A request cannot have both transfer encoding and content length", "Headers were " + headers);
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

                    requestListener.onHeaders(method, requestUri, protocol, headers, state != State.COMPLETE);
                    return; // jump out of this method to parse the body (if there is one)
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
                                throw new InvalidRequestException(400, "Content-Length set after chunked encoding sent", "Headers were " + headers);
                            }
                            long prev = this.bodyLength;
                            try {
                                this.bodyLength = Long.parseLong(val);
                            } catch (NumberFormatException e) {
                                throw new InvalidRequestException(400, "Invalid content-length header", "Header was " + cur);
                            }
                            if (prev != -1 && prev != this.bodyLength) {
                                throw new InvalidRequestException(400, "Multiple content-length headers", "First was " + prev + " and then " + bodyLength);
                            }
                            break;
                        case "transfer-encoding":
                            if (bodyLength > -1) {
                                throw new InvalidRequestException(400, "Can't have transfer-encoding with content-length", "Headers were " + headers);
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
    }

    private void parseFixedLengthBody(ByteBuffer bb) {
        long expectedRemaining = bodyLength - bodyBytesRead;
        int toRead = (int)Math.min(expectedRemaining, bb.remaining());
        bodyBytesRead += toRead;

        var view = bb.slice(bb.position(), toRead);
        bb.position(bb.position() + toRead);
        requestListener.onBody(view);

        if (bodyBytesRead == bodyLength) {
            state = State.COMPLETE;
        }
    }

    private void parseChunkedBody(ByteBuffer bb) throws InvalidRequestException {
        if (chunkState != ChunkState.DATA) {
            while (bb.hasRemaining()) {
                byte c = bb.get();
                if (c == '\r') {
                    continue;
                }
                if (chunkState == ChunkState.TRAILER_NAME) {

                    if (c == '\n') {
                        if (cur.length() > 0) {
                            throw new InvalidRequestException(400, "HTTP Protocol error - trailer line had no value", "While reading a header name (" + cur + ") a newline was found, but there was no ':' first.");
                        }
                        if (trailers == null) {
                            trailers = MuHeaders.EMPTY;
                        }
                        state = State.COMPLETE;
                        break;
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
                        throw new InvalidRequestException(400, "Invalid character in chunk size declaration: " + c, "Why");
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
                        throw new InvalidRequestException(400, "Extra data after chunk was supposed to end: " + c, "Why2");
                    }
                } else {
                    throw new IllegalStateException("Unexpected state " + state);
                }
            }
        }

        if (chunkState == ChunkState.DATA) {
            while (bb.hasRemaining()) {
                if (curChunkSize > 0) {
                    int size = (int) Math.min(curChunkSize, bb.remaining());
                    bodyBytesRead += size;
                    curChunkSize -= size;
                    var view = bb.slice(bb.position(), size);
                    requestListener.onBody(view);
                    bb.position(bb.position() + size);
                } else {
                    chunkState = ChunkState.DATA_DONE;
                    break;
                }
            }
        }
    }

    private void append(byte c) {
        cur.append((char) c);
    }

    interface RequestListener {
        void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, boolean hasBody);

        void onBody(ByteBuffer buffer);

        void onRequestComplete(MuHeaders trailers);
    }

}
