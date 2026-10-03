package io.muserver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Formatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * PrintWriter semantics without holding a Java monitor across response I/O.
 * On Java 21, PrintWriter's monitor can pin a virtual thread while an HTTP/2
 * write waits for the connection writer or flow-control credit. Keep the JDK
 * buffering and encoding, but guard output operations with a reentrant lock.
 */
final class ResponsePrintWriter extends PrintWriter {
    private final ReentrantLock writeLock = new ReentrantLock();
    private @Nullable Formatter responseFormatter;

    ResponsePrintWriter(OutputStream output, Charset charset) {
        super(new BufferedWriter(new OutputStreamWriter(output, charset)));
    }

    private void ensureOpen() throws IOException {
        if (out == null) throw new IOException("Stream closed");
    }

    @Override public void write(int value) {
        writeLock.lock();
        try {
            ensureOpen();
            out.write(value);
        } catch (InterruptedIOException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void write(char[] chars, int offset, int length) {
        writeLock.lock();
        try {
            ensureOpen();
            out.write(chars, offset, length);
        } catch (InterruptedIOException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void write(String text, int offset, int length) {
        writeLock.lock();
        try {
            ensureOpen();
            out.write(text, offset, length);
        } catch (InterruptedIOException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void flush() {
        writeLock.lock();
        try {
            ensureOpen();
            out.flush();
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void close() {
        writeLock.lock();
        try {
            if (out != null) {
                out.close();
                out = null;
            }
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public boolean checkError() {
        writeLock.lock();
        try {
            return super.checkError();
        } finally {
            writeLock.unlock();
        }
    }

    private void newLine() {
        try {
            ensureOpen();
            out.write(System.lineSeparator());
        } catch (InterruptedIOException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            setError();
        }
    }

    // PrintWriter's println and format methods acquire their own monitor, so
    // overriding write alone would still pin callers of these entry points.
    // Inherited print, append and printf methods delegate to our overrides.
    @Override public void println() {
        writeLock.lock();
        try {
            newLine();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void println(@Nullable String value) {
        writeLock.lock();
        try {
            print(value);
            newLine();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void println(char[] value) {
        writeLock.lock();
        try {
            print(value);
            newLine();
        } finally {
            writeLock.unlock();
        }
    }

    @Override public void println(boolean value) { println(String.valueOf(value)); }
    @Override public void println(char value) { println(String.valueOf(value)); }
    @Override public void println(int value) { println(String.valueOf(value)); }
    @Override public void println(long value) { println(String.valueOf(value)); }
    @Override public void println(float value) { println(String.valueOf(value)); }
    @Override public void println(double value) { println(String.valueOf(value)); }
    @Override public void println(@Nullable Object value) { println(String.valueOf(value)); }

    @Override public PrintWriter format(String format, Object... arguments) {
        return format(Locale.getDefault(), format, arguments);
    }

    @Override public PrintWriter format(@Nullable Locale locale, String format, Object... arguments) {
        writeLock.lock();
        try {
            ensureOpen();
            if (responseFormatter == null || !Objects.equals(responseFormatter.locale(), locale)) {
                responseFormatter = new Formatter(this, locale);
            }
            responseFormatter.format(locale, format, arguments);
        } catch (IOException error) {
            setError();
        } finally {
            writeLock.unlock();
        }
        return this;
    }
}
