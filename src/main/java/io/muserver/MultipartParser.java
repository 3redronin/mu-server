package io.muserver;

import javax.ws.rs.core.MediaType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class MultipartParser {

    private static final MediaType DEFAULT_CONTENT_TYPE = new MediaType("text", "plain", StandardCharsets.US_ASCII.name());

    private enum State {
        PART_BOUNDARY, H_NAME, H_VALUE, FIXED_BODY, COMPLETE;

    }

    private final String messageEndDelimiter;
    private final String partDelimiter;
    private State state = State.PART_BOUNDARY;
    private StringBuffer cur = new StringBuffer();
    private MuHeaders headers = new MuHeaders();

    private String curHeader;
    private List<String> curVals;
    private long bodyLength = -1;
    private long bodyBytesRead;
    private int headerSize = 0;

    private void reset() {
        curHeader = null;
        curVals = null;
        curOutputStream = null;
        contentDisposition = null;
        contentType = null;
        bodyLength = -1;
        bodyBytesRead = 0;
        cur.setLength(0);
        state = State.PART_BOUNDARY;
    }

    private ParameterizedHeaderWithValue contentDisposition;
    private MediaType contentType;
    private OutputStream curOutputStream;

    private CaseSensitiveRequestParameters parts = new CaseSensitiveRequestParameters();


    public MultipartParser(String boundary) {
        this.partDelimiter = "--" + boundary;
        this.messageEndDelimiter = "--" + boundary + "--";
    }

    public RequestParameters parse(InputStream is) throws IOException, InvalidRequestException {
        byte[] buffer = new byte[8192];


        int read;
        while ((read = is.read(buffer)) > -1) {
            if (read > 0) {
                ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read);
                while (bb.hasRemaining()) {
                    if (state == State.FIXED_BODY) {
                        int length = (int) Math.min(bodyLength, (long) bb.remaining());
                        parseFixedLengthBody(buffer, bb.position(), length);
                        bb.position(bb.position() + length);
                    } else if (state == State.COMPLETE) {
                        bb.position(bb.limit());
                    } else {
                        parsePartHeaders(bb);
                    }
                }
            }
        }

        return parts;
    }


    private void parsePartHeaders(ByteBuffer bb) throws InvalidRequestException {
        while (bb.hasRemaining()) {

            byte c = bb.get();

            headerSize++;
            if (headerSize > 8192) {
                throw new InvalidRequestException(431, "Multipart Header Fields Too Large", "Header length (including all white space) reached " + headerSize + " bytes.");
            }

            if (c == '\r') {
                continue; // as per spec, \r can be ignored in line endings when parsing
            }

            if (state == State.PART_BOUNDARY) {
                if (c == '\n') {
                    if (cur.length() > 0) {
                        if (cur.toString().equals(messageEndDelimiter)) {
                            state = State.COMPLETE;
                            return;
                        } else if (!partDelimiter.equals(cur.toString())) {
                            throw new InvalidRequestException(400, "Invalid multipart body", "Expected " + partDelimiter + " but got " + cur);
                        }
                        state = State.H_NAME;
                        cur.setLength(0);
                    } // else ignore blank lines
                } else if (Parser.isTChar(c)) {
                    append(c);
                } else {
                    throw new InvalidRequestException(400, "Invalid character in part boundary", "Got a " + c + " character in the request line");
                }
            } else if (state == State.H_NAME) {

                if (c == ' ') {
                    throw new InvalidRequestException(400, "HTTP protocol error: space in header name", "Shouldn't have a space while in " + state);
                } else if (c == '\n') {
                    if (cur.length() > 0) {
                        throw new InvalidRequestException(400, "A header name included a line feed character", "Value was " + cur);
                    }
                    cur.setLength(0);


                    if (contentType == null) {
                        contentType = DEFAULT_CONTENT_TYPE;
                    }
                    curOutputStream = new ByteArrayOutputStream((int) bodyLength);
                    state = State.FIXED_BODY;

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
                            long prev = this.bodyLength;
                            try {
                                this.bodyLength = Long.parseLong(val);
                            } catch (NumberFormatException e) {
                                throw new InvalidRequestException(400, "Invalid content-length header on multipart", "Header was " + cur);
                            }
                            if (prev != -1 && prev != this.bodyLength) {
                                throw new InvalidRequestException(400, "Multiple content-length headers on multipart", "First was " + prev + " and then " + bodyLength);
                            }
                            break;
                        case "content-disposition":
                            this.contentDisposition = ParameterizedHeaderWithValue.fromString(cur.toString()).get(0);
                            break;
                        case "content-type":
                            this.contentType = MediaTypeParser.fromString(cur.toString());
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

    private void parseFixedLengthBody(byte[] data, int offset, int length) throws IOException {

        curOutputStream.write(data, offset, length);
        bodyBytesRead += length;

        if (bodyBytesRead == bodyLength) {
            curOutputStream.close();
            if (contentType.getType().equals("text")) {
                String charset = contentType.getParameters().getOrDefault("charset", "US-ASCII");
                String formName = contentDisposition.parameters().get("name");

                ByteArrayOutputStream baos = (ByteArrayOutputStream) curOutputStream;
                parts.add(formName, baos.toString(charset));
            }
            reset();
        }
    }


    private void append(byte c) {
        cur.append((char) c);
    }


}
