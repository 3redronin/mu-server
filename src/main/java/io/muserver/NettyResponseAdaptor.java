package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.Future;

abstract class NettyResponseAdaptor implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(NettyResponseAdaptor.class);
    protected final boolean isHead;
    protected OutputState outputState = OutputState.NOTHING;
    protected final NettyRequestAdapter request;
    protected ChannelFuture lastAction;
    private final Headers headers;
    protected int status = 200;
    private PrintWriter writer;
    private OutputStream outputStream;
    protected long bytesStreamed = 0;
    protected long declaredLength = -1;
    private final int httpVersion;

    protected enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE, FINISHED, DISCONNECTED
    }

    void onClientDisconnected() {
        outputState = OutputState.DISCONNECTED;
    }

    NettyResponseAdaptor(NettyRequestAdapter request, Headers headers, int httpVersion) {
        this.headers = headers;
        this.request = request;
        this.isHead = request.method() == Method.HEAD;
        this.httpVersion = httpVersion;
        this.headers.set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
    }

    public int status() {
        return status;
    }

    public void status(int value) {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        status = value;
    }

    protected void startStreaming() {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start streaming when state is " + outputState);
        }
        outputState = OutputState.STREAMING;
        if (httpVersion == 1) {


        } else {

            Http2Headers entries = ((H2Headers) headers).entries;
            entries.status(httpStatus().codeAsText());

        }
    }

    protected void throwIfFinished() {
        if (outputState == OutputState.FULL_SENT || outputState == OutputState.FINISHED || outputState == OutputState.DISCONNECTED) {
            throw new IllegalStateException("Cannot write data as response has already completed");
        }
    }

    public Future<Void> writeAsync(String text) {
        return write(textToBuffer(text), false);
    }

    ChannelFuture write(ByteBuffer data) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        return write(Unpooled.wrappedBuffer(data), false);
    }

    abstract ChannelFuture write(ByteBuf data, boolean sync);


    public void sendChunk(String text) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = write(textToBuffer(text), true);
    }

    protected ByteBuf textToBuffer(String text) {
        Charset charset = StandardCharsets.UTF_8;
        MediaType type = headers().contentType();
        if (type != null) {
            String encoding = type.getParameters().get("charset");
            if (!Mutils.nullOrEmpty(encoding)) {
                charset = Charset.forName(encoding);
            }
        }
        return Unpooled.copiedBuffer(text, charset);
    }

    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }



    public Headers headers() {
        return headers;
    }

    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.LAX.encode(cookie.nettyCookie));
    }

    public OutputStream outputStream() {
        if (this.outputStream == null) {
            startStreaming();
            this.outputStream = new BufferedOutputStream(new ChunkedHttpOutputStream(this), 4096);
        }
        return this.outputStream;
    }

    public PrintWriter writer() {
        if (this.writer == null) {
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8);
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return outputState != OutputState.NOTHING;
    }

    boolean clientDisconnected() {
        return outputState == OutputState.DISCONNECTED;
    }

    ChannelFuture complete(boolean forceDisconnect) {
        if (outputState == OutputState.FINISHED) {
            return lastAction;
        }
        boolean shouldDisconnect = forceDisconnect || !request.isKeepAliveRequested();
        boolean isFixedLength = declaredLength >= 0;
        if (outputState == OutputState.NOTHING) {
            sendEmptyResponse(isFixedLength);
        } else if (outputState == OutputState.STREAMING) {

            if (!isHead) {
                Mutils.closeSilently(writer);
                Mutils.closeSilently(outputStream);
            }
            boolean badFixedLength = !isHead && isFixedLength && declaredLength != bytesStreamed && status != 304;
            if (badFixedLength) {
                shouldDisconnect = true;
                if (connectionOpen()) {
                    log.warn("Closing client connection for " + request + " because " + declaredLength + " bytes was the " +
                        "expected length, however " + bytesStreamed + " bytes were sent.");
                }
            } else {
                lastAction = writeLastContentMarker();
            }
        }

        if (shouldDisconnect) {
            if (lastAction == null) {
                lastAction = closeConnection();
            } else {
                lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
            }
        }
        if (this.outputState != OutputState.DISCONNECTED) {
            this.outputState = OutputState.FINISHED;
        }
        return lastAction;
    }

    protected abstract ChannelFuture closeConnection();

    protected abstract boolean connectionOpen();

    protected abstract ChannelFuture writeLastContentMarker();

    protected abstract void sendEmptyResponse(boolean isFixedLength);

    protected HttpResponseStatus httpStatus() {
        return HttpResponseStatus.valueOf(status());
    }

    static class EmptyHttpResponse extends DefaultFullHttpResponse {
        EmptyHttpResponse(HttpResponseStatus status) {
            super(HttpVersion.HTTP_1_1, status, false);
        }
    }

}
