package io.muserver;

import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

class RequestParser {


    private final RequestListener requestListener;

    private State state = State.RL_METHOD;
    private StringBuffer cur = new StringBuffer();
    private Method method;
    private URI requestUri;
    private HttpVersion protocol;
    private MuHeaders headers = new MuHeaders();
    private MuHeaders trailers;
    private String curHeader;
    private List<String> curVals;
    private GrowableByteBufferInputStream body;
    private long bodyLength = -1; // -2 is chunked
    private long bodyBytesRead;
    private ChunkState chunkState;
    private long curChunkSize = -1;

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
        body = null;
        bodyLength = -1;
        bodyBytesRead = 0;
        chunkState = null;
        curChunkSize = -1;
    }

    RequestParser(RequestListener requestListener) {
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
            if (state == State.COMPLETE) {
                throw new InvalidRequestException(400, "Request body too long", "More request was found even though no more was expected.");
            } else if (state == State.FIXED_BODY) {
                parseFixedLengthBody(bb);
            } else if (state == State.CHUNKED_BODY) {
                parseChunkedBody(bb);
            } else {
                parseReqLineAndHeaders(bb);
            }
        }
        if (state == State.COMPLETE) {
            requestListener.onRequestComplete(trailers);
            reset();
        }
    }

    private void parseReqLineAndHeaders(ByteBuffer bb) throws InvalidRequestException {
        while (bb.hasRemaining()) {

            byte c = bb.get();
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
                } else {
                    // todo: throw if invalid char
                    append(c);
                }
            } else if (state == State.RL_URI) {

                if (c == ' ') {
                    requestUri = URI.create(cur.toString());
                    state = State.RL_PROTO;
                    cur.setLength(0);
                } else {
                    append(c);
                }
            } else if (state == State.RL_PROTO) {
                if (c == '\n') {
                    this.protocol = HttpVersion.fromRequestLine(cur.toString());
                    if (this.protocol == null) {
                        throw new InvalidRequestException(505, "HTTP Version Not Supported", "Http version was " + protocol);
                    }
                    this.state = State.H_NAME;
                    cur.setLength(0);
                    state = State.H_NAME;
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
                                body = GrowableByteBufferInputStream.EMPTY_STREAM;
                            } else {
                                body = new GrowableByteBufferInputStream();
                                state = State.FIXED_BODY;
                            }
                        } else {
                            body = new GrowableByteBufferInputStream();
                            chunkState = ChunkState.SIZE;
                            state = State.CHUNKED_BODY;
                        }
                    } else {
                        state = State.COMPLETE;
                    }

                    requestListener.onHeaders(method, requestUri, protocol, headers, body);
                    return; // jump out of this method to parse the body (if there is one)
                } else if (c == ':') {

                    String header = cur.toString();
                    this.curHeader = header.toLowerCase();
                    if (headers.contains(header)) {
                        curVals = headers.getAll(header);
                    } else {
                        curVals = new ArrayList<>();
                        headers.put(header, curVals);
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
                    switch (curHeader) {
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

    private void parseFixedLengthBody(ByteBuffer bb) throws InvalidRequestException {
        int size = bb.limit() - bb.position();
        bodyBytesRead += size;
        ByteBuffer copy = ByteBuffer.allocate(size);
        copy.put(bb);
        copy.flip();
        body.handOff(copy);

        if (bodyBytesRead == bodyLength) {
            body.close();
            state = State.COMPLETE;
        } else if (bodyBytesRead > bodyLength) {
            throw new InvalidRequestException(400, "Request body too long", "The client declared a body length of " + bodyLength + " but has already sent " + bodyBytesRead);
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
                        body.close();
                        state = State.COMPLETE;
                        break;
                    } else if (c == ':') {
                        String header = cur.toString();
                        this.curHeader = header.toLowerCase();

                        if (trailers == null) {
                            trailers = new MuHeaders();
                        }
                        if (trailers.contains(header)) {
                            curVals = trailers.getAll(header);
                        } else {
                            curVals = new ArrayList<>();
                            trailers.put(header, curVals);
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
                int size = (int) Math.min(curChunkSize, bb.limit() - bb.position());
                bodyBytesRead += size;
                curChunkSize -= size;
                byte[] copy = new byte[size];
                bb.get(copy, 0, size);
                body.handOff(ByteBuffer.wrap(copy));
                if (curChunkSize == 0) {
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
        void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, InputStream body);

        void onRequestComplete(MuHeaders trailers);
    }

}
