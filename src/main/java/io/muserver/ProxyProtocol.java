package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Bounded, unbuffered pre-TLS parser. Never reads beyond the PROXY preamble. */
final class ProxyProtocol {
    private static final byte[] SIGNATURE = "\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.US_ASCII);
    private ProxyProtocol() {}

    static ProxiedConnectionInfo read(Socket socket, long deadlineNanos) throws IOException {
        int oldTimeout = socket.getSoTimeout();
        InputStream source = socket.getInputStream();
        InputStream bounded = new InputStream() {
            private void beforeRead() throws IOException {
                long left = MonotonicTime.nanosUntil(deadlineNanos);
                if (left <= 0) throw new SocketTimeoutException("PROXY header timeout");
                long millis = TimeUnit.NANOSECONDS.toMillis(left - 1) + 1;
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, millis)));
            }
            @Override public int read() throws IOException {
                while (true) {
                    beforeRead();
                    try { return source.read(); }
                    catch (SocketTimeoutException timeout) {
                        // SO_TIMEOUT can expire before a deadline longer than Integer.MAX_VALUE milliseconds.
                        if (MonotonicTime.nanosUntil(deadlineNanos) <= 0) throw timeout;
                    }
                }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                while (true) {
                    beforeRead();
                    try { return source.read(bytes, offset, length); }
                    catch (SocketTimeoutException timeout) {
                        if (MonotonicTime.nanosUntil(deadlineNanos) <= 0) throw timeout;
                    }
                }
            }
        };
        try {
            ProxiedConnectionInfo info = parse(bounded);
            if (MonotonicTime.nanosUntil(deadlineNanos) <= 0) throw new SocketTimeoutException("PROXY header timeout");
            return info;
        } finally { socket.setSoTimeout(oldTimeout); }
    }

    static ProxiedConnectionInfo parse(InputStream source) throws IOException {
        int first = source.read();
        if (first == 'P') {
            byte[] line = new byte[107];
            line[0] = (byte) first;
            // The shortest valid v1 line is "PROXY UNKNOWN\r\n" (15 bytes).
            readExact(source, line, 1, 14);
            int size = 15;
            for (int i = 1; i < size - 1; i++) {
                if (line[i - 1] == '\r' && line[i] == '\n') throw invalid();
            }
            while (true) {
                if (line[size - 2] == '\r' && line[size - 1] == '\n') {
                    return parseV1(new String(line, 0, size - 2, StandardCharsets.US_ASCII));
                }
                if (size == line.length) throw new IOException("PROXY v1 header exceeds 107 bytes");
                int value = source.read();
                if (value < 0) throw new EOFException("Truncated PROXY v1 header");
                line[size++] = (byte) value;
            }
        }
        if (first != SIGNATURE[0]) throw invalid();
        // All v2 headers have 16 fixed bytes, including the payload length.
        byte[] header = new byte[16];
        header[0] = (byte) first;
        readExact(source, header, 1, 15);
        for (int i = 1; i < SIGNATURE.length; i++) if (header[i] != SIGNATURE[i]) throw invalid();
        int command = header[12] & 255;
        if (command != 0x20 && command != 0x21) throw invalid();
        int length = ((header[14] & 255) << 8) | (header[15] & 255);
        Payload payload = new Payload(source, length);
        if (command == 0x20) {
            payload.discard(length);
            return new Info(null, 0, null, 0);
        }
        int family = (header[13] & 255) >>> 4;
        int transport = header[13] & 15;
        if (family > 3 || transport > 2) throw invalid();
        if (family == 0 || transport == 0) {
            payload.tlvs();
            return new Info(null, 0, null, 0);
        }
        int addressSize = family == 1 ? 4 : family == 2 ? 16 : 108;
        int required = 2 * addressSize + (family == 3 ? 0 : 4);
        byte[] addresses = payload.bytes(required);
        String src, dst;
        int srcPort = 0, dstPort = 0;
        if (family == 3) {
            src = unixAddress(addresses, 0); dst = unixAddress(addresses, addressSize);
        } else {
            src = ipAddress(addresses, 0, addressSize);
            dst = ipAddress(addresses, addressSize, addressSize);
            srcPort = unsignedShort(addresses, addressSize * 2);
            dstPort = unsignedShort(addresses, addressSize * 2 + 2);
        }
        payload.tlvs();
        return new Info(src, srcPort, dst, dstPort);
    }

    /** Only the address block and a fixed scratch buffer are retained. */
    private static final class Payload {
        private final InputStream source;
        private final byte[] scratch;
        private int remaining;
        Payload(InputStream source, int remaining) {
            this.source = source; this.remaining = remaining;
            this.scratch = new byte[Math.min(4096, remaining)];
        }
        byte[] bytes(int count) throws IOException {
            if (count > remaining) throw invalid();
            byte[] result = readExact(source, count);
            remaining -= count;
            return result;
        }
        void discard(int count) throws IOException {
            if (count > remaining) throw invalid();
            while (count > 0) {
                int read = source.read(scratch, 0, Math.min(count, scratch.length));
                if (read < 0) throw new EOFException("Truncated PROXY v2 payload");
                if (read == 0) throw new IOException("PROXY input made no progress");
                count -= read; remaining -= read;
            }
        }
        void tlvs() throws IOException {
            while (remaining > 0) {
                byte[] header = bytes(3);
                discard(unsignedShort(header, 1));
            }
        }
    }

    private static String ipAddress(byte[] bytes, int offset, int size) {
        StringBuilder result = new StringBuilder();
        int step = size == 4 ? 1 : 2;
        for (int i = 0; i < size; i += step) {
            if (i > 0) result.append(step == 1 ? '.' : ':');
            if (step == 1) result.append(bytes[offset + i] & 255);
            else result.append(Integer.toHexString(unsignedShort(bytes, offset + i)));
        }
        return result.toString();
    }

    private static String unixAddress(byte[] payload, int offset) {
        int length = 0;
        while (length < 108 && payload[offset + length] != 0) length++;
        return new String(payload, offset, length, StandardCharsets.US_ASCII);
    }

    private static int unsignedShort(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static ProxiedConnectionInfo parseV1(String line) throws IOException {
        String[] parts = line.split(" ", -1);
        if (parts.length < 2 || !parts[0].equals("PROXY")) throw invalid();
        if (parts[1].equals("UNKNOWN")) return new Info(null, 0, null, 0);
        if (parts.length != 6 || (!parts[1].equals("TCP4") && !parts[1].equals("TCP6"))) throw invalid();
        validateAddress(parts[2], parts[1].equals("TCP4"));
        validateAddress(parts[3], parts[1].equals("TCP4"));
        return new Info(parts[2], port(parts[4]), parts[3], port(parts[5]));
    }

    private static void validateAddress(String address, boolean ipv4) throws IOException {
        if (ipv4) {
            String[] pieces = address.split("\\.", -1);
            if (pieces.length != 4) throw invalid();
            for (String piece : pieces) if (piece.length() > 3 || port(piece) > 255) throw invalid();
        } else {
            // Restrict to numeric literals before calling InetAddress: no DNS queries.
            if (!address.contains(":") || !address.matches("[0-9a-fA-F:.]+")) throw invalid();
            if (address.contains(".")) validateAddress(address.substring(address.lastIndexOf(':') + 1), true);
            InetAddress.getByName(address);
        }
    }

    private static int port(String value) throws IOException {
        if (value.isEmpty() || value.length() > 5 || (value.length() > 1 && value.charAt(0) == '0')) throw invalid();
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') throw invalid();
            result = result * 10 + c - '0';
        }
        if (result > 65535) throw invalid();
        return result;
    }

    private static byte[] readExact(InputStream source, int size) throws IOException {
        byte[] data = new byte[size];
        readExact(source, data, 0, size);
        return data;
    }

    private static void readExact(InputStream source, byte[] data, int offset, int length) throws IOException {
        int end = offset + length;
        while (offset < end) {
            int count = source.read(data, offset, end - offset);
            if (count < 0) throw new EOFException("Truncated PROXY header");
            if (count == 0) throw new IOException("PROXY input made no progress");
            offset += count;
        }
    }

    private static IOException invalid() { return new IOException("Invalid PROXY protocol header"); }

    private static final class Info implements ProxiedConnectionInfo {
        private final @Nullable String source, destination;
        private final int sourcePort, destinationPort;
        Info(@Nullable String source, int sourcePort, @Nullable String destination, int destinationPort) {
            this.source = source; this.sourcePort = sourcePort;
            this.destination = destination; this.destinationPort = destinationPort;
        }
        @Override public @Nullable String sourceAddress() { return source; }
        @Override public @Nullable String destinationAddress() { return destination; }
        @Override public int sourcePort() { return sourcePort; }
        @Override public int destinationPort() { return destinationPort; }
        @Override public boolean equals(@Nullable Object other) {
            if (!(other instanceof Info)) return false;
            Info that = (Info) other;
            return sourcePort == that.sourcePort && destinationPort == that.destinationPort
                && Objects.equals(source, that.source) && Objects.equals(destination, that.destination);
        }
        @Override public int hashCode() { return Objects.hash(source, sourcePort, destination, destinationPort); }
        @Override public String toString() { return source + ":" + sourcePort + "->" + destination + ":" + destinationPort; }
    }
}
