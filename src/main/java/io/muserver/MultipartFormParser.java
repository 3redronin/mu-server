package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.muserver.ParseUtils.CR;
import static io.muserver.ParseUtils.LF;

class MultipartFormParser {

    private final Path fileUploadDir;
    private final InputStream body;
    private final ByteBuffer crlfDashDashBoundary;
    private final byte[] array;
    private final ByteBuffer bb;
    private static final ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
    private State state = State.PREAMBLE_SOL;
    private Charset formCharset;

    public State state() {
        return state;
    }

    MultipartFormParser(Path fileUploadDir, String boundary, InputStream body, int bufferSize, Charset formCharset) {
        this.formCharset = formCharset;
        this.fileUploadDir = fileUploadDir;
        this.array = new byte[bufferSize];
        this.body = body;
        this.crlfDashDashBoundary = ByteBuffer.wrap(("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII));
        this.bb = ByteBuffer.wrap(array).flip();
    }

    public MultipartForm parseFully() throws IOException {

        var form = new MultipartForm();
        try {
            discardPreamble();

            Mu3Headers headers = readPartHeaders();
            while (headers != null) {

                String keyName = null;
                String filename = null;
                var cdv = headers.get(HeaderNames.CONTENT_DISPOSITION);
                if (cdv != null) {
                    var cds = ParameterizedHeaderWithValue.fromString(cdv);
                    if (cds.size() == 1) {
                        var cd = cds.get(0);
                        if ("form-data".equals(cd.value())) {
                            keyName = cd.parameter("name");
                            filename = cd.parameter("filename");
                        }
                    }
                }

                if (keyName == null) {
                    discardBody();
                } else if (filename == null) {
                    var charsetToUse = formCharset;
                    var ct = headers.contentType();
                    if (ct != null && ct.getParameters().containsKey("charset")) {
                        charsetToUse = Charset.forName(ct.getParameters().get("charset"));
                    }
                    var value = readString(charsetToUse);
                    form.addValue(keyName, value);
                    if ("_charset_".equals(keyName)) {
                        formCharset = Charset.forName(value);
                    }
                } else {
                    var ct = headers.contentType();
                    if (ct != null) {
                        var temp = Files.createTempFile(fileUploadDir, "muserverupload", ".tmp");
                        var muf = new MuUploadedFile2(temp, ct.toString(), filename);
                        form.addFile(keyName, muf);

                        try (var fos = new FileOutputStream(temp.toFile());
                            var bfos = new BufferedOutputStream(fos)) {
                            writeBodyTo(bfos);
                        }
                        // stream data into file

                    }
                }
                headers = readPartHeaders();
            }

            discardEpilogue();
        } catch (Throwable e) {
            // delete any saved files
            form.cleanup();
            throw e;
        }
        return form;
    }

    private void writeBodyTo(OutputStream out) throws IOException {
        if (state != State.BODY) throw new IllegalStateException("state is " + state);

        while (state != State.BOUNDARY_END) {
            readIfEmpty();
            var nextCR = indexOf(bb, CR);
            if (nextCR == -1) {
                // No EOL in the buffer so can just decode what we got and ask for more data
                if (bb.hasRemaining()) {
                    out.write(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining());
                }
                bb.clear().flip();
            } else {
                // write everything from current position up to \r
                out.write(bb.array(), bb.arrayOffset() + bb.position(), nextCR - bb.position());
                bb.position(nextCR);

                // see if we have enough space for a boundary
                if (bb.capacity() - bb.position() < crlfDashDashBoundary.remaining()) {
                    bb.compact().flip();
                }
                while (bb.remaining() < crlfDashDashBoundary.remaining()) {
                    readMore(true);
                }
                // see if the body buffer starts with \r\n--boundary
                var mismatch = crlfDashDashBoundary.mismatch(bb);
                if (mismatch == -1 || mismatch == crlfDashDashBoundary.remaining()) {
                    // we are at the boundary. Write no more. Move position past the boundary.
                    bb.position(bb.position() + crlfDashDashBoundary.remaining());
                    state = State.BOUNDARY_END;
                } else if (mismatch > 0) {
                    // although the first char is \r, only the first 'mismatch' chars match.
                    // we can write up to that mismatch at least, then check again on the next loop if we have a match
                    out.write(bb.array(), bb.arrayOffset() + bb.position(), mismatch);
                    bb.position(bb.position() + mismatch);
                } else {
                    throw new IllegalStateException("How is mismatch 0 here?");
                }
            }
        }
    }

    private int readMore(boolean compact) throws IOException {
        if (compact || bb.position() == bb.capacity()) {
            bb.compact().flip();
        }
        var array = bb.array();
        int maxCanRead = bb.capacity() - bb.limit();
        var bytesRead = body.read(array, bb.arrayOffset() + bb.limit(), maxCanRead);
        if (bytesRead == -1) {
            throw new EOFException("state=" + state);
        }
        if (bytesRead > 0) {
            bb.limit(bb.limit() + bytesRead);
        }
        return bytesRead;
    }

    void discardPreamble() throws IOException {
        while (true) {
            readIfEmpty();
            while (bb.hasRemaining()) {
                if (state == State.PREAMBLE_SOL) {
                    // read until we can check for "--boundary" which happens to be crlfDashDashBoundary - 2 length
                    var dashDashBoundary = crlfDashDashBoundary.duplicate().position(2);
                    while (bb.remaining() < dashDashBoundary.remaining()) {
                        readMore(true);
                    }
                    var mismatch = dashDashBoundary.mismatch(bb);
                    if (mismatch == -1 || mismatch == dashDashBoundary.remaining()) {
                        bb.position(bb.position() + dashDashBoundary.remaining());
                        state = State.BOUNDARY_END;
                        return;
                    }
                    state = State.PREAMBLE_TEXT;
                } if (state == State.PREAMBLE_TEXT) {
                    var nextCR = indexOf(bb, CR);
                    if (nextCR == -1) {
                        // no EOL in the buffer - can just clear it and read in data again
                        bb.clear().flip();
                    } else {
                        bb.position(nextCR + 1);
                        while (bb.remaining() == 0) {
                            if (bb.position() == bb.capacity()) {
                                bb.clear().flip();
                            }
                            if (readMore(false) == -1) throw new EOFException();
                        }
                        var maybeLF = bb.get();
                        if (maybeLF == LF) {
                            state = State.PREAMBLE_SOL;
                        }
                    }
                } else {
                    throw new IllegalStateException("Unhandled state " + state);
                }
            }
        }

    }

    private void readIfEmpty() throws IOException {
        while (!bb.hasRemaining()) {
            readMore(true);
        }
    }

    public @Nullable Mu3Headers readPartHeaders() throws IOException {
        if (state != State.BOUNDARY_END) throw new IllegalStateException("state is " + state);
        while (bb.remaining() < 2) {
            readMore(true);
        }

        var next1 = bb.get();
        var next2 = bb.get();
        if (next1 == '-' && next2 == '-') {
            state = State.END_OF_BODY;
            return null;
        } else if (next1 == '\r' && next2 == '\n') {
            state = State.PART_START;
        } else {
            throw HttpException.badRequest("Invalid characters after boundary " + (int) next1 + " and " + (int) next2);
        }

        var headers = new Mu3Headers();

        while (state != State.BODY) {

            while (bb.remaining() < 2) {
                readMore(true);
            }
            if (bb.get(bb.position()) == '\r' && bb.get(bb.position() + 1) == '\n') {
                bb.position(bb.position() + 2);
                state = State.BODY;
            } else {
                var colon = indexOf(bb, (byte)':');
                while (colon == -1) {
                    readMore(true);
                    colon = indexOf(bb, (byte)':');
                }
                int start = bb.arrayOffset() + bb.position();
                var headerName = new String(bb.array(), start, colon - start, StandardCharsets.US_ASCII).toLowerCase();
                bb.position(colon + 1);

                var cr = indexOf(bb, CR);
                var lf = indexOf(bb, LF);
                while (cr == -1 || lf == -1 || (lf != cr + 1)) {
                    readMore(true);
                    cr = indexOf(bb, CR);
                    lf = indexOf(bb, LF);
                }

                start = bb.arrayOffset() + bb.position();
                var headerValue = new String(bb.array(), start, cr - start, StandardCharsets.UTF_8).trim();
                headers.add(headerName, headerValue);
                bb.position(lf + 1);
                state = State.HEADER_NAME;
            }


        }
        return headers;
    }

    public String readString(Charset charset) throws IOException {
        if (state != State.BODY) throw new IllegalStateException("state is " + state);

        CharsetDecoder decoder = charset.newDecoder();

        var sb = new StringBuilder();
        var cbuffer = CharBuffer.allocate(Math.min(128, array.length));

        while (state != State.BOUNDARY_END) {
            readIfEmpty();
            var nextCR = indexOf(bb, CR);
            if (nextCR == -1) {
                // No EOL in the buffer so can just decode what we got and ask for more data
                if (bb.hasRemaining()) {
                    var result = decoder.decode(bb, cbuffer, false);
                    if (result.isError()) {
                        throw HttpException.badRequest("Invalid character decoding in body part");
                    }
                    if (result.isOverflow()) {
                        cbuffer.flip();
                        sb.append(cbuffer);
                        cbuffer.clear();
                    }
                    bb.compact().flip();
                    if (result.isUnderflow()) {
                        readMore(false);
                    }
                } else {
                    bb.clear().flip();
                }
            } else {
                decodeUntil(nextCR, sb, decoder, cbuffer);
                if (bb.capacity() - bb.position() < crlfDashDashBoundary.remaining()) {
                    bb.compact().flip();
                }
                while (bb.remaining() < crlfDashDashBoundary.capacity()) {
                    readMore(true);
                }
                var mismatch = crlfDashDashBoundary.mismatch(bb);
                if (mismatch == -1 || mismatch == crlfDashDashBoundary.remaining()) {
                    bb.position(bb.position() + crlfDashDashBoundary.remaining());
                    state = State.BOUNDARY_END;
                } else if (mismatch > 0) {
                    decodeUntil(bb.position() + mismatch, sb, decoder, cbuffer);
                } else {
                    throw new IllegalStateException("How is mismatch 0 here?");
                }
            }
        }
        var result = decoder.decode(emptyBuffer, cbuffer, true);
        if (result.isError()) throw HttpException.badRequest("Invalid character decoding in body part: " + result);
        sb.append(cbuffer.flip());
        state = State.BOUNDARY_END;
        return sb.toString();
    }

    public void discardBody() throws IOException {
        if (state != State.BODY) throw new IllegalStateException("state is " + state);
        // we're basically in the same situation as in preamble mode - so just pretend we are there again
        state = State.PREAMBLE_SOL;
        discardPreamble();
    }

    private void decodeUntil(int index, StringBuilder sb, CharsetDecoder decoder, CharBuffer cbuffer) {
        var tempLimit = bb.limit();
        bb.limit(index);
        var result = decoder.decode(bb, cbuffer, false);
        if (result.isError()) throw HttpException.badRequest("Invalid character decoding in body part: " + result);
        if (result.isOverflow()) {
            sb.append(cbuffer.flip());
            cbuffer.clear();
            decodeUntil(index, sb, decoder, cbuffer);
        }
        bb.limit(tempLimit);
    }

    public void discardEpilogue() throws IOException {
        if (state != State.END_OF_BODY) throw new IllegalStateException("state is " + state);
        while (body.read(this.array) != -1) {
            // consume
        }
        state = State.EOF;
    }

    enum State {
        PREAMBLE_SOL, PREAMBLE_TEXT, BOUNDARY_END, PART_START, BODY, END_OF_BODY, HEADER_NAME, HEADER_NAME_ENDED, HEADER_VALUE_ENDING, EOF
    }

    private static int indexOf(ByteBuffer source, byte b) {
        for (var i = source.position(); i < source.limit(); i++) {
            if (source.get(i) == b) {
                return i;
            }
        }
        return -1;
    }

}


