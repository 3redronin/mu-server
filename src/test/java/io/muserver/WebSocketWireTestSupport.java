package io.muserver;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Raw client for WebSocket wire-level regressions. */
final class WebSocketWireTestSupport implements AutoCloseable {
    final Socket socket;
    final DataInputStream input;
    final OutputStream output;
    final int handshakeBytes;

    WebSocketWireTestSupport(MuServer server) throws IOException {
        socket = new Socket(server.uri().getHost(), server.uri().getPort());
        socket.setSoTimeout(3000);
        socket.setTcpNoDelay(true);
        input = new DataInputStream(socket.getInputStream());
        output = socket.getOutputStream();
        byte[] request = ("GET / HTTP/1.1\r\nHost: " + server.uri().getAuthority() + "\r\n"
            + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        handshakeBytes = request.length;
        output.write(request);
        output.flush();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        int tail = 0;
        do {
            int next = input.readUnsignedByte();
            response.write(next);
            tail = (tail << 8) | next;
            assertTrue(response.size() < 8192, "HTTP upgrade headers too large");
        } while (tail != 0x0d0a0d0a);
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 101 "));
    }

    static byte[] frame(boolean fin, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            out.writeByte(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            out.writeByte(0x80 | 126);
            out.writeShort(payload.length);
        } else {
            out.writeByte(0x80 | 127);
            out.writeLong(payload.length);
        }
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.writeByte(payload[i] ^ mask[i % 4]);
        return bytes.toByteArray();
    }

    void send(boolean fin, int opcode, byte[] payload) throws IOException {
        output.write(frame(fin, opcode, payload));
        output.flush();
    }

    byte[] readFrame(int expectedOpcode) throws IOException {
        assertEquals(0x80 | expectedOpcode, input.readUnsignedByte(), "FIN and opcode");
        int length = input.readUnsignedByte();
        assertEquals(0, length & 0x80, "Server frame must be unmasked");
        if (length == 126) length = input.readUnsignedShort();
        else if (length == 127) length = Math.toIntExact(input.readLong());
        assertTrue(length >= 0 && length <= 1024 * 1024, "Unexpected response length");
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
