package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class WebsocketConnection implements MuWebSocketSession {
    private static final Logger log = LoggerFactory.getLogger(WebsocketConnection.class);
    private ByteBuffer buffer;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final WebSocketHandlerBuilder.Settings settings;

    private WebsocketSessionState state = WebsocketSessionState.NOT_STARTED;
    private final Mu3Http1Connection httpConnection;
    private final MuWebSocket webSocket;
    private boolean closeReceived = false;
    private boolean closeSent = false;
    private final Lock writeLock = new ReentrantLock();
    private final ByteBuffer pingBuffer;
    private ReadState readState = ReadState.NONE;

    private enum ReadState {
        NONE, TEXT, BINARY
    }

    WebsocketConnection(Mu3Http1Connection httpConnection, MuWebSocket webSocket, WebSocketHandlerBuilder.Settings settings) {
        this.httpConnection = httpConnection;
        this.webSocket = webSocket;
        this.settings = settings;
        if (settings.pingIntervalMillis == 0) {
            pingBuffer = null;
        } else {
            // a ping is 8 random bytes following by a long (the time)
            // the random bytes are used to identify if we created the payload received on a pong
            pingBuffer = ByteBuffer.allocate(16);
            byte[] header = new byte[8];
            HttpsConfigBuilder.random.nextBytes(header);
            pingBuffer.put(header);
        }
    }


    private void startPinging() {
        httpConnection.server().getScheduledExecutor().schedule(() -> {
            writeLock.lock();
            try {
                pingBuffer.position(8)
                    .limit(16)
                    .putLong(System.currentTimeMillis())
                    .flip();
                sendPing(pingBuffer);
                if (state == WebsocketSessionState.OPEN) {
                    startPinging();
                }
            } catch (IOException e) {
                if (state == WebsocketSessionState.OPEN) {
                    // force an IO exception on the read operation in runAndBlockUntilDone()
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                }
            } finally {
                writeLock.unlock();
            }

        }, settings.pingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public void runAndBlockUntilDone(InputStream inputStream, OutputStream outputStream, byte[] readBuffer) throws Exception {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.buffer = ByteBuffer.wrap(readBuffer).flip();

        try {
            state = WebsocketSessionState.OPEN;
            webSocket.onConnect(this);

            if (settings.pingIntervalMillis > 0) {
                startPinging();
            }

            while (!closeReceived) {

                // make sure we at least have the minimum sized buffer
                readAtLeast(2);
                int firstByte = buffer.get() & 0xFF;
                boolean fin = (firstByte & 0x80) != 0;
                boolean rsv1 = (firstByte & 0x40) != 0;
                boolean rsv2 = (firstByte & 0x20) != 0;
                boolean rsv3 = (firstByte & 0x10) != 0;
                if (rsv1 || rsv2 || rsv3) {
                    throw frameError(1002, "Unsupported websocket reserved keywords");
                }

                int opcode = firstByte & 0x0F;

                int secondByte = buffer.get() & 0xFF;
                boolean masked = (secondByte & 0x80) != 0;
                long payloadLength = secondByte & 0b01111111;

                if (!masked) {
                    throw frameError(1002, "Unmasked client data");
                }
                if (payloadLength == 126) {
                    readAtLeast(2);
                    payloadLength = buffer.getShort() & 0xFFFF;
                } else if (payloadLength == 127) {
                    readAtLeast(8);
                    payloadLength = buffer.getLong();
                }
                if (payloadLength > settings.maxFramePayloadLength) {
                    throw frameError(1009, "Max payload length of " + settings.maxFramePayloadLength + " exceeded with frame size " + payloadLength);
                }

                byte[] maskingKey = new byte[4];
                readAtLeast(4);
                buffer.get(maskingKey, 0, 4);

                // in practice, the max length is an int so fits in a byte array
                int payloadLen = (int) payloadLength;
                var slice = readAndUnmaskPayload(payloadLen, maskingKey);


                if (closeReceived) {
                    log.info("Ignoring " + opcode + " message as close received already");
                } else if (opcode == 0x0) {
                    // continuation frame
                    if (readState == ReadState.TEXT) {
                        webSocket.onTextFragment(slice, fin);
                    } else if (readState == ReadState.BINARY) {
                        webSocket.onBinaryFragment(slice, fin);
                    } else {
                        // If there was ever a new continuable message type, this would fail rather than ignore it
                        throw frameError(1002, "Continuation frame received unexpectedly");
                    }
                    if (fin) {
                        readState = ReadState.NONE;
                    }
                } else if (opcode == 0x1) {
                    // text frame
                    if (readState != ReadState.NONE) {
                        throw frameError(1002, "New text message sent while expecting continuation frame");
                    }
                    if (fin) {
                        var text = StandardCharsets.UTF_8.newDecoder().decode(slice).toString();
                        webSocket.onText(text);
                    } else {
                        readState = ReadState.TEXT;
                        webSocket.onTextFragment(slice, false);
                    }
                } else if (opcode == 0x2) {
                    // binary frame
                    if (readState != ReadState.NONE) {
                        throw frameError(1002, "New binary message received while expecting continuation frame");
                    }
                    if (fin) {
                        webSocket.onBinary(slice);
                    } else {
                        readState = ReadState.BINARY;
                        webSocket.onBinaryFragment(slice, false);
                    }
                } else if (opcode == 0x8) {
                    if (state == WebsocketSessionState.OPEN) {
                        state = WebsocketSessionState.CLIENT_CLOSING;
                    }
                    closeReceived = true;
                    // close frame
                    short closeCode;
                    String reason = "";
                    if (payloadLen >= 2) {
                        closeCode = slice.getShort();
                        if (slice.hasRemaining()) {
                            reason = StandardCharsets.UTF_8.decode(slice).toString();
                        }
                    } else {
                        closeCode = 1005;
                    }
                    log.info("Client close: " + closeCode + " " + reason);
                    webSocket.onClientClosed(closeCode, reason);
                    if (closeSent) {
                        if (state == WebsocketSessionState.CLIENT_CLOSING) {
                            state = WebsocketSessionState.CLIENT_CLOSED;
                        } else if (state == WebsocketSessionState.SERVER_CLOSING) {
                            state = WebsocketSessionState.SERVER_CLOSED;
                        }
                    }
                } else if (opcode == 0x9) {
                    webSocket.onPing(slice);
                } else if (opcode == 0xA) {
                    webSocket.onPong(slice);
                }
                // ignore unknown types


            }
            log.info("It's over");
        } catch (Throwable e) {
            if (state != WebsocketSessionState.TIMED_OUT) {
                webSocket.onError(e);
                state = WebsocketSessionState.ERRORED;
            }
        }
    }

    private void unmask(ByteBuffer buffer, byte[] maskingKey, int payloadLength) {
        var offset = buffer.position();
        for (int i = 0; i < payloadLength; i++) {
            byte mask = maskingKey[i % 4];
            int pos = offset + i;
            byte maskedB = buffer.get(pos);
            byte unmaskedB = (byte)(maskedB ^ mask);
            buffer.put(pos, unmaskedB);
        }
    }

    private ByteBuffer readAndUnmaskPayload(int len, byte[] maskingKey) throws IOException {
        if (len <= buffer.capacity()) {
            readAtLeast(len);
            unmask(buffer, maskingKey, len);
            var tempLimit = buffer.limit();
            buffer.limit(buffer.position() + len);
            var slice = buffer.slice();
            buffer.position(buffer.limit());
            buffer.limit(tempLimit);
            return slice;
        } else {
            var full = ByteBuffer.allocate(len);
            var toRead = len;
            while (toRead > 0) {
                int nextLen = Math.min(toRead, buffer.capacity());
                var slice = readAndUnmaskPayload(nextLen, maskingKey);
                full.put(slice);
                toRead = toRead - nextLen;
            }
            return full.flip();
        }
    }

    private void readAtLeast(int minBytes) throws IOException {
        if (minBytes > buffer.capacity()) throw new IllegalArgumentException("This buffer is not big enough");
        while (buffer.remaining() < minBytes) {
            if (buffer.capacity() - buffer.limit() < minBytes) {
                buffer.compact().flip();
            }
            int read = inputStream.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.capacity() - buffer.limit());
            if (read == -1) {
                throw new ClientDisconnectedException();
            } else {
                httpConnection.onBytesRead(read);
            }
            buffer.limit(buffer.limit() + read);
        }
    }

    private Exception frameError(int code, String reason) throws IOException {
        close(code, reason);
        return new ProtocolException(reason);
    }

    @Override
    public boolean closeReceived() {
        return closeReceived;
    }

    @Override
    public boolean closeSent() {
        return closeSent;
    }


    private MessageWritingState messageWritingState = MessageWritingState.NONE;

    void onTimeout() {
        try {
            this.webSocket.onError(new TimeoutException("Connection idle timeout"));
        } catch (Exception ignored) {
        } finally {
            state = WebsocketSessionState.TIMED_OUT;
        }
    }

    private enum MessageWritingState {
        NONE, TEXT, BINARY, ERROR
    }

    @Override
    public void sendText(String message) throws IOException {
        var payload = message.getBytes(StandardCharsets.UTF_8);
        writeFragment((byte)0b10000001, payload, 0, payload.length, MessageWritingState.NONE, MessageWritingState.NONE);
    }

    @Override
    public void sendTextFragment(ByteBuffer fragment, boolean isLastFragment) throws IOException {
        writeLock.lock();
        try {
            if (isLastFragment && messageWritingState == MessageWritingState.NONE) {
                // this is just a non-fragmented full message, so use the plain send
                sendText(StandardCharsets.UTF_8.decode(fragment).toString());
            } else {
                var payload = arrayBuffer(fragment);
                int off = payload.arrayOffset() + payload.position();
                int len = payload.remaining();
                if (!isLastFragment && messageWritingState == MessageWritingState.NONE) {
                    // the first message of a fragmented text message
                    writeFragment((byte) 0b00000001, payload.array(), off, len, MessageWritingState.NONE, MessageWritingState.TEXT);
                } else if (!isLastFragment && messageWritingState == MessageWritingState.TEXT) {
                    // a middle fragment of a text message
                    writeFragment((byte) 0b00000000, payload.array(), off, len, MessageWritingState.TEXT, MessageWritingState.TEXT);
                } else if (isLastFragment && messageWritingState == MessageWritingState.TEXT) {
                    // the last fragment of a text message
                    writeFragment((byte) 0b10000000, payload.array(), off, len, MessageWritingState.TEXT, MessageWritingState.NONE);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendBinary(ByteBuffer message) throws IOException {
        var payload = arrayBuffer(message);
        writeFragment((byte)0b10000010, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), MessageWritingState.NONE, MessageWritingState.NONE);
    }

    @Override
    public void sendBinaryFragment(ByteBuffer message, boolean isLastFragment) throws IOException {
        writeLock.lock();
        try {
            if (isLastFragment && messageWritingState == MessageWritingState.NONE) {
                // this is just a non-fragmented full message, so use the plain send
                sendBinary(message);
            } else {
                var payload = arrayBuffer(message);
                int off = payload.arrayOffset() + payload.position();
                int len = payload.remaining();
                if (!isLastFragment && messageWritingState == MessageWritingState.NONE) {
                    // the first message of a fragmented binary message
                    writeFragment((byte) 0b00000010, payload.array(), off, len, MessageWritingState.NONE, MessageWritingState.BINARY);
                } else if (!isLastFragment && messageWritingState == MessageWritingState.BINARY) {
                    // a middle fragment of a binary message
                    writeFragment((byte) 0b00000000, payload.array(), off, len, MessageWritingState.BINARY, MessageWritingState.BINARY);
                } else if (isLastFragment && messageWritingState == MessageWritingState.BINARY) {
                    // the last fragment of a binary message
                    writeFragment((byte) 0b10000000, payload.array(), off, len, MessageWritingState.BINARY, MessageWritingState.NONE);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendPing(ByteBuffer payload) throws IOException {
        payload = arrayBuffer(payload);
        writeFragment((byte)0b10001001, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), null, null);
    }

    @Override
    public void sendPong(ByteBuffer payload) throws IOException {
        payload = arrayBuffer(payload);
        writeFragment((byte)0b10001010, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), null, null);
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            writeFragment((byte)0b10001000, null, 0, 0, null, null);
            if (!closeSent) {
                if (state == WebsocketSessionState.OPEN) {
                    state = WebsocketSessionState.SERVER_CLOSING;
                }
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close(int statusCode, String reason) throws IOException {
        if (statusCode < 1000 || statusCode > 4999) {
            throw new IllegalArgumentException("Websocket closure codes must be between 1000 and 4999 (inclusive)");
        }
        writeLock.lock();
        try {
            if (state == WebsocketSessionState.OPEN) {
                state = WebsocketSessionState.SERVER_CLOSING;
            }
            if (reason == null || reason.isEmpty()) {
                byte[] closeCodeBytes = new byte[2];
                closeCodeBytes[0] = (byte) ((statusCode >> 8) & 0xFF);
                closeCodeBytes[1] = (byte) (statusCode & 0xFF);
                writeFragment((byte)0b10001000, closeCodeBytes, 0, 2, null, null);
            } else {
                var reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
                var payload = new byte[reasonBytes.length + 2];
                payload[0] = (byte) ((statusCode >> 8) & 0xFF);
                payload[1] = (byte) (statusCode & 0xFF);
                System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
                writeFragment((byte)0b10001000, payload, 0, payload.length, null, null);
            }
            if (!closeSent) {
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
        }
    }

    private static ByteBuffer arrayBuffer(ByteBuffer source) {
        if (source.hasArray()) {
            return source;
        }
        var arr = new byte[source.remaining()];
        source.get(arr);
        return ByteBuffer.wrap(arr);
    }

    private void writeFragment(byte firstByte, byte[] payload, int payloadOffset, int payloadLen, MessageWritingState expectedState, MessageWritingState endState) throws IOException {
        var header = header(firstByte, payloadLen);
        writeLock.lock();
        try {
            if (expectedState != null && messageWritingState != expectedState) {
                throw new IllegalStateException("Expected state " + expectedState + " but was " + messageWritingState);
            }
            if (closeSent) {
                throw new IllegalStateException("Cannot write websocket messages after close frame sent");
            }
            outputStream.write(header, 0, header.length);
            if (payloadLen > 0) {
                outputStream.write(payload, payloadOffset, payloadLen);
            }
            outputStream.flush();
            if (endState != null) {
                messageWritingState = endState;
            }
        } catch (IOException e) {
            messageWritingState = MessageWritingState.ERROR;
            state = WebsocketSessionState.ERRORED;
            try {
                webSocket.onError(e);
            } catch (Exception userException) {
                e.addSuppressed(userException);
            }
            throw e;
        } finally {
            writeLock.unlock();
        }
    }

    private byte[] header(byte type, int payloadLength) {
        if (payloadLength <= 125) {
            // 1-byte case
            return new byte[] { type, (byte) payloadLength };
        } else if (payloadLength <= 65535) {
            // 3-byte case (first byte 126 + 2 bytes for length)
            return new byte[] {
                type,
                (byte) 126,
                (byte) ((payloadLength >> 8) & 0xFF),   // Higher byte
                (byte) (payloadLength & 0xFF)           // Lower byte
            };
        } else {
            // 9-byte case (first byte 127 + 8 bytes for length)
            return new byte[] {
                type,
                (byte) 127,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                (byte) ((payloadLength >> 24) & 0xFF),
                (byte) ((payloadLength >> 16) & 0xFF),
                (byte) ((payloadLength >> 8) & 0xFF),
                (byte) (payloadLength & 0xFF)
            };
        }
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return httpConnection.remoteAddress();
    }

    @Override
    public WebsocketSessionState state() {
        return state;
    }

    @Override
    public Long pongLatencyMillis(ByteBuffer pongPayload) {
        if (pongPayload == null) throw new NullPointerException("pongPayload");
        if (pingBuffer == null) return null;
        if (pongPayload.remaining() != 16) return null;
        // verify header
        for (int i = 0; i < 8; i++) {
            if (pingBuffer.get(i) != pongPayload.get(i)) return null;
        }
        // okay we probably made it. Now get the timestamp from it.
        var pingTime = pingBuffer.getLong(8);
        return System.currentTimeMillis() - pingTime;
    }

    @Override
    public String toString() {
        return "WebsocketConnection{" +
            "state=" + state +
            ", remote=" + remoteAddress() +
            '}';
    }
}
