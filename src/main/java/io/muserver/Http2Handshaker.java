package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Implementation of section 3.4 of RFC9113 <a href="https://www.rfc-editor.org/rfc/rfc9113.html#name-http-2-connection-preface">rfc9113.html#name-http-2-connection-preface</a>
 */
class Http2Handshaker {

    private Http2Handshaker() {}

    private static final byte[] CLIENT_CONNECTION_PREFACE =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /*
    Processes the client connection  preface
     */
    static Http2Settings handshake(Http2Connection connection, Http2Settings serverSettings, Http2Settings clientSettings, ByteBuffer buffer, InputStream clientIn, OutputStream clientOut) throws IOException, Http2Exception {
        // handshake start
        readClientPreface(clientIn);
        var newClientSettings = readClientSettings(buffer, clientIn, clientSettings);
        serverSettings.writeTo(connection, clientOut);
        Http2Settings.ACK.writeTo(connection, clientOut);
        clientOut.flush();
        return newClientSettings;
    }

    private static void readClientPreface(InputStream inputStream) throws IOException, Http2Exception {
        byte[] prefaceBuffer = new byte[24];
        int bytesRead = inputStream.read(prefaceBuffer);

        if (bytesRead != 24 || !isClientPrefaceValid(prefaceBuffer)) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Invalid connection prefix");
        }
    }

    private static boolean isClientPrefaceValid(byte[] preface) {
        for (int i = 0; i < CLIENT_CONNECTION_PREFACE.length; i++) {
            if (preface[i] != CLIENT_CONNECTION_PREFACE[i]) {
                return false;
            }
        }
        return true;
    }

    private static Http2Settings readClientSettings(ByteBuffer buffer, InputStream clientIn, Http2Settings existingSettings) throws IOException, Http2Exception {
        Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);
        var header = Http2FrameHeader.readFrom(buffer);
        if (header.frameType() != Http2FrameType.SETTINGS) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Received " + header + " during client connection preface");
        }
        Mutils.readAtLeast(buffer, clientIn, header.length());
        var settingsFrame = Http2Settings.readFrom(header, buffer);
        if (settingsFrame.isAck){
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Client acked settings before sent");
        }
        return settingsFrame.copyIfChanged(existingSettings);
    }

}
