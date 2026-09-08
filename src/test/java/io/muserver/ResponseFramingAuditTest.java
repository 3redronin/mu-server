package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ServerUtils;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static io.muserver.RFCTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseFramingAuditTest {
    @Test
    void http10StreamingResponseClosesToDelimitContent() throws Exception {
        try (var server = ServerUtils.httpsServerForTest("http")
            .addHandler(Method.GET, "/", (request, response, params) -> response.sendChunk("hello"))
            .start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            assertEquals("hello", wire.substring(wire.indexOf("\r\n\r\n") + 4), wire);
        }
    }

    @Test
    void http1HeadDiscardsContent() throws Exception {
        try (var server = ServerUtils.httpsServerForTest("http")
            .addHandler(Method.HEAD, "/", (request, response, params) -> response.write("hello"))
            .start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            assertEquals("", wire.substring(wire.indexOf("\r\n\r\n") + 4), wire);
        }
    }

    @Test
    void http2Empty205EndsStream() throws Exception {
        try (var server = ServerUtils.httpsServerForTest("h2")
            .addHandler(Method.GET, "/hello", (request, response, params) -> response.status(205))
            .start();
             var client = new H2Client();
             var con = client.connect(server)) {
            con.socket().setSoTimeout(2000);
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort()))).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("205", response.headers().get(":status"));
            assertTrue(response.endStream());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 205, 304})
    void http1SuppressesContentForBodylessStatuses(int status) throws Exception {
        try (var server = ServerUtils.httpsServerForTest("http")
            .addHandler(Method.GET, "/", (request, response, params) -> {
                response.status(status);
                response.write("hello");
            }).start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 " + status), wire);
            assertEquals("", wire.substring(wire.indexOf("\r\n\r\n") + 4), wire);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 205, 304})
    void http2SuppressesContentForHeadAndBodylessStatuses(int status) throws Exception {
        Method method = status == 200 ? Method.HEAD : Method.GET;
        try (var server = ServerUtils.httpsServerForTest("h2")
            .addHandler(method, "/hello", (request, response, params) -> {
                response.status(status);
                response.write("hello");
            }).start();
             var client = new H2Client();
             var con = client.connect(server)) {
            con.socket().setSoTimeout(2000);
            var headers = getHelloHeaders(server.uri().getPort());
            headers.set(":method", method.name());
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(Integer.toString(status), response.headers().get(":status"));
            int payloadBytes = 0;
            if (!response.endStream()) {
                Http2DataFrame frame;
                do {
                    frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                    payloadBytes += frame.payloadLength();
                } while (!frame.endStream());
            }
            assertEquals(0, payloadBytes, "Unexpected response body for " + method + " status " + status);
        }
    }

    @Test
    void http1UnwrittenDeclaredBodyDoesNotHangClient() throws Exception {
        try (var server = ServerUtils.httpsServerForTest("http")
            .addHandler(Method.GET, "/", (request, response, params) -> {
                response.headers().set("content-length", "5");
            }).start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            // Read through transport EOF: HTTP clients differ in how they expose
            // a closed response that contains fewer bytes than Content-Length.
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            int headerEnd = wire.indexOf("\r\n\r\n");
            assertTrue(headerEnd > 0, wire);
            assertTrue(wire.substring(0, headerEnd).contains("content-length: 5"), wire);
            assertEquals("", wire.substring(headerEnd + 4), wire);
        }
    }
}
