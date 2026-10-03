package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ResponsePrintWriterTest {
    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "UTF-16LE", "ISO-8859-1"})
    void outputMatchesJdkAcrossEveryEntryPoint(String charsetName) {
        Charset charset = Charset.forName(charsetName);
        var expected = new ByteArrayOutputStream();
        var actual = new ByteArrayOutputStream();
        var stock = new PrintWriter(expected, false, charset);
        var response = new ResponsePrintWriter(actual, charset);
        Consumer<PrintWriter> operations = writer -> {
            writer.write('a'); writer.write("héllo 世界 ");
            writer.write("\uD83D"); writer.write("\uDE00");
            writer.write("012345", 1, 3); writer.write(new char[]{'é', 'b', 'c'}, 0, 2);
            writer.print(true); writer.print('z'); writer.print(123); writer.print(123L);
            writer.print(1.25f); writer.print(2.5d); writer.print(new char[]{'a', 'b'});
            writer.print((String) null); writer.print((Object) null);
            writer.println(); writer.println(true); writer.println('é'); writer.println(123);
            writer.println(123L); writer.println(1.25f); writer.println(2.5d);
            writer.println(new char[]{'a', 'é'}); writer.println((String) null); writer.println((Object) null);
            assertSame(writer, writer.append('x')); assertSame(writer, writer.append("héllo"));
            assertSame(writer, writer.append("012345", 1, 4)); writer.append(null);
            assertSame(writer, writer.format(Locale.FRANCE, "%s %.2f%n", "hello", 1.5));
            assertSame(writer, writer.printf(Locale.US, "%s %.2f%n", "world", 2.5));
            writer.format((Locale) null, "%s %.2f%n", "none", 3.5);
            writer.format("%s %d%n", "default", 7); writer.printf("%s%n", "printf");
            writer.write("large-é-世界".repeat(5000)); writer.flush();
            assertFalse(writer.checkError()); writer.close(); writer.close();
            assertFalse(writer.checkError());
        };
        operations.accept(stock); operations.accept(response);
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
    }

    @Test void errorsAndClosedWriterMatchJdk() {
        for (boolean response : new boolean[]{false, true}) {
            OutputStream failing = new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("controlled failure"); }
            };
            PrintWriter writer = response ? new ResponsePrintWriter(failing, StandardCharsets.UTF_8)
                : new PrintWriter(failing, false, StandardCharsets.UTF_8);
            writer.write("hello"); assertTrue(writer.checkError()); writer.close();
            writer.write('x'); writer.println("closed"); writer.format("closed"); assertTrue(writer.checkError());
            var output = new ByteArrayOutputStream();
            writer = response ? new ResponsePrintWriter(output, StandardCharsets.UTF_8)
                : new PrintWriter(output, false, StandardCharsets.UTF_8);
            writer.close(); writer.write("closed"); assertTrue(writer.checkError());
            assertEquals(0, output.size());
        }
    }

    @Test void invalidWritesAndFormatsRetainTheJdkExceptions() {
        for (boolean response : new boolean[]{false, true}) {
            var output = new ByteArrayOutputStream();
            try (PrintWriter writer = response ? new ResponsePrintWriter(output, StandardCharsets.UTF_8)
                : new PrintWriter(output, false, StandardCharsets.UTF_8)) {
                assertThrows(IndexOutOfBoundsException.class, () -> writer.write(new char[2], 1, 2));
                assertThrows(IllegalArgumentException.class, () -> writer.format("%q", 1));
                writer.println("still usable"); assertFalse(writer.checkError());
                assertEquals("still usable" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
            }
        }
    }

    @Test void interruptedWriteRestoresTheInterruptFlag() {
        for (boolean response : new boolean[]{false, true}) {
            OutputStream interrupted = new OutputStream() {
                @Override public void write(int value) throws IOException { throw new InterruptedIOException("interrupted"); }
            };
            PrintWriter writer = response ? new ResponsePrintWriter(interrupted, StandardCharsets.UTF_8)
                : new PrintWriter(interrupted, false, StandardCharsets.UTF_8);
            try {
                assertFalse(Thread.interrupted()); writer.write("x".repeat(32768));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); writer.close(); Thread.interrupted();
            }
        }
    }

    @Test void concurrentLinesAndFormatsStayWhole() throws Exception {
        var output = new ByteArrayOutputStream();
        var writer = new ResponsePrintWriter(output, StandardCharsets.UTF_8);
        var workers = Executors.newFixedThreadPool(8);
        try {
            for (int worker = 0; worker < 8; worker++) {
                final int id = worker;
                workers.submit(() -> {
                    for (int index = 0; index < 200; index++) {
                        if (index % 2 == 0) writer.println(id + ":" + index + ":" + "é".repeat(40));
                        else writer.format(Locale.ROOT, "%d:%d:%s%n", id, index, "é".repeat(40));
                    }
                });
            }
        } finally {
            workers.shutdown(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); writer.close();
        }
        String[] lines = output.toString(StandardCharsets.UTF_8).split(System.lineSeparator());
        assertEquals(1600, lines.length); assertEquals(1600, new HashSet<>(Arrays.asList(lines)).size());
        for (String line : lines) assertTrue(line.matches("[0-7]:[0-9]+:" + "é".repeat(40)), line);
    }
}
