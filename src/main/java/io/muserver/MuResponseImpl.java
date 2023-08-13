package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.RedirectionException;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.muserver.ContentTypes.TEXT_PLAIN_UTF8;

public class MuResponseImpl implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(MuResponseImpl.class);

    private final MuExchangeData data;
    private final AsynchronousSocketChannel tlsChannel;
    private int status = 200;
    private final MuHeaders headers = new MuHeaders();
    private MuHeaders trailers = null;
    private ResponseState state = ResponseState.NOTHING;
    private OutputStream outputStream;
    private PrintWriter writer;

    public MuResponseImpl(MuExchangeData data, AsynchronousSocketChannel tlsChannel) {
        this.data = data;
        this.tlsChannel = tlsChannel;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public void status(int value) {
        this.status = value;
    }

    @Override
    public void write(String text) throws IOException {
        if (state != ResponseState.NOTHING) throw new IllegalStateException("Cannot write text");
        Charset charset = setDefaultContentType();
        ByteBuffer body = charset.encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, body.remaining());
        ByteBuffer headerBuf = headersBuffer(true, headers);
        blockingWrite(headerBuf, body);
        state = ResponseState.FULL_SENT;
        data.connection.onResponseCompleted(this);
    }

    private void blockingWrite(ByteBuffer... buffers) throws IOException {
        try {
            for (ByteBuffer buffer : buffers) {
                String s = new String(buffer.array(), buffer.position(), buffer.limit());
                log.info(">>\n" + s.replace("\r", "\\r").replace("\n", "\\n\r\n"));


                // TODO use scattering write
                while (buffer.hasRemaining()) {
                    int written = tlsChannel.write(buffer).get(10, TimeUnit.SECONDS).intValue();
                    if (written > 0) {
                        log.info("Wrote " + written + " bytes");
                        data.server.stats.onBytesSent(written);
                    } else if (written == -1) {
                        state = ResponseState.ERRORED;
                        throw new IOException("Write failed");
                    }
                }
            }
        } catch (InterruptedException e) {
            state = ResponseState.ERRORED;
            throw new InterruptedIOException();
        } catch (ExecutionException e) {
            state = ResponseState.ERRORED;
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause);
        } catch (TimeoutException e) {
            state = ResponseState.TIMED_OUT;
            throw new IOException("Timed out writing response", e);
        }
    }

    private ByteBuffer headersBuffer(boolean reqLine, MuHeaders headers) {
        var sb = new StringBuilder();
        if (reqLine) {
            sb.append(data.httpVersion.version()).append(' ').append(status).append(' ').append("OK").append("\r\n");
        }
        for (Map.Entry<String, List<String>> entry : headers.all().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        sb.append("\r\n");
        return StandardCharsets.US_ASCII.encode(sb.toString());
    }

    @Override
    public void sendChunk(String text) throws IOException {
        PrintWriter w = writer();
        w.write(text);
        w.flush();
    }

    private void sendChunk(ByteBuffer headersBuffer, ByteBuffer chunk) throws IOException {
        var chunkStart = StandardCharsets.US_ASCII.encode(Integer.toHexString(chunk.remaining()) + "\r\n");
        var chunkEnd = StandardCharsets.US_ASCII.encode("\r\n");
        if (headersBuffer != null) {
            blockingWrite(headersBuffer, chunkStart, chunk, chunkEnd);
        } else {
            blockingWrite(chunkStart, chunk, chunkEnd);
        }
    }

    public void end() throws IOException {
        if (state == ResponseState.STREAMING) {
            state = ResponseState.FINISHING;
            if (writer != null) {
                writer.close();
                writer = null;
            }
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (headers.containsValue(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true)) {
                boolean sendTrailers = trailers != null && Headtils.getParameterizedHeaderWithValues(data.requestHeaders, HeaderNames.TE)
                    .stream().anyMatch(v -> v.value().equalsIgnoreCase("trailers"));
                if (sendTrailers) {
                    var trailersBuffer = headersBuffer(false, trailers);
                    blockingWrite(StandardCharsets.US_ASCII.encode("0\r\n"), trailersBuffer);
                } else {
                    blockingWrite(StandardCharsets.US_ASCII.encode("0\r\n\r\n"));
                }
            }
            state = ResponseState.FINISHED;
        } else if (state == ResponseState.NOTHING) {
            ByteBuffer headerBuf = headersBuffer(true, headers);
            blockingWrite(headerBuf);
            state = ResponseState.FINISHED;
        }
    }


    private Charset setDefaultContentType() {
        Charset charset = NettyRequestAdapter.bodyCharset(headers, false);
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, charset == StandardCharsets.UTF_8 ? TEXT_PLAIN_UTF8 : "text/plain;charset=" + charset.name());
        }
        return charset;
    }

    @Override
    public void redirect(String url) {
        redirect(URI.create(url));
    }

    @Override
    public void redirect(URI uri) {
        throw new RedirectionException(Response.Status.FOUND, uri);
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    @Override
    public void addCookie(Cookie cookie) {

    }

    @Override
    public OutputStream outputStream() {
        return outputStream(4096);
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        throwIfAsync();
        OutputStream adapter = new OutputStream() {
            private int state = 0;
            private boolean chunked = false;
            @Override
            public void write(int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.println("Sending " + (len - off) + " bytes");
                if (state == 2) throw new IOException("Writing to a closed stream");
                ByteBuffer hb;
                if (state == 0) {
                    MuResponseImpl.this.state = ResponseState.STREAMING;
                    state = 1;
                    if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
                        headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.APPLICATION_OCTET_STREAM);
                    }
                    if (!headers.contains(HeaderNames.CONTENT_LENGTH)) {
                        chunked = true;
                        headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
                    }
                    hb = headersBuffer(true, headers);
                } else {
                    hb = null;
                }
                if (chunked) {
                    sendChunk(hb, ByteBuffer.wrap(b, off, len));
                } else if (hb != null) {
                    blockingWrite(hb, ByteBuffer.wrap(b, off, len));
                } else {
                    blockingWrite(ByteBuffer.wrap(b, off, len));
                }
            }

            @Override
            public void close() throws IOException {
                if (state != 2) {
                    System.out.println("Closed");
                    state = 2;
                    end();
                }
            }
        };
        this.outputStream = bufferSize == 0 ? adapter : new BufferedOutputStream(adapter, bufferSize);
        return adapter;
    }

    @Override
    public PrintWriter writer() {
        if (this.writer == null) {
            Charset charset = setDefaultContentType();
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), charset);
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    private void throwIfAsync() {
        //TODO
//        if (data.isAsync()) {
//            throw new IllegalStateException("Cannot use blocking methods when in async mode");
//        }
    }


    @Override
    public boolean hasStartedSendingData() {
        return state != ResponseState.NOTHING;
    }

    @Override
    public ResponseState responseState() {
        return state;
    }

    @Override
    public Headers trailers() {
        if (trailers == null) {
            trailers = new MuHeaders();
        }
        return trailers;
    }

    public void onCancelled(ResponseState responseState) {
        state = responseState;
    }
}
