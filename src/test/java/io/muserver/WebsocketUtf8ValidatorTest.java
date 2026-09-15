package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebsocketUtf8ValidatorTest {
    @Test
    void matchesJdkForCompleteInputsAndRejectsInvalidPrefixesNoLater() {
        Random random = new Random(6455);
        for (int trial = 0; trial < 20000; trial++) {
            byte[] bytes;
            if (trial % 2 == 0) {
                bytes = new byte[random.nextInt(9)];
                random.nextBytes(bytes);
            } else {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    int codePoint;
                    do { codePoint = random.nextInt(0x110000); }
                    while (codePoint >= 0xd800 && codePoint <= 0xdfff);
                    text.appendCodePoint(codePoint);
                }
                bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                if (trial % 3 == 0) bytes[random.nextInt(bytes.length)] = (byte) random.nextInt(256);
            }
            WebsocketUtf8Validator validator = new WebsocketUtf8Validator();
            boolean accepted = true;
            for (int i = 0; i < bytes.length; i++) {
                accepted = validator.accept(bytes[i] & 255);
                boolean decoderAccepted = !StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes, 0, i + 1), CharBuffer.allocate(bytes.length), false).isError();
                // The JDK can defer an error until more bytes arrive (for example ED A0).
                // Our validator may reject earlier, but must never accept a prefix it rejects.
                if (!decoderAccepted) assertFalse(accepted, "Prefix trial " + trial + " byte " + i);
                if (!accepted) break;
            }
            boolean decoderComplete;
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                decoderComplete = true;
            } catch (CharacterCodingException invalid) {
                decoderComplete = false;
            }
            assertEquals(decoderComplete, accepted && validator.isComplete(), "Complete trial " + trial);
            validator.reset();
            assertEquals(true, validator.accept('a') && validator.isComplete());
        }
    }
}
