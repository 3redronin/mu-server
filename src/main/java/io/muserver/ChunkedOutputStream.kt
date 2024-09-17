package io.muserver

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

internal class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    private val isClosed = AtomicBoolean(false)
    companion object{
        private val endChunk = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
    override fun write(b: Int) {
        val array = ByteArray(6)
        array[0] = 1
        array[1] = 13
        array[2] = 10
        array[3] = b.toByte()
        array[4] = 13
        array[5] = 10
        out.write(array)
    }

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0) {
            val lenBytes = len.toString(16).toByteArray(StandardCharsets.US_ASCII)
            out.write(lenBytes, 0, lenBytes.size)
            out.write(CRLF, 0 , 2)
            out.write(b, off, len)
            out.write(CRLF, 0, 2)
        }
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {

            // don't actually close the underlying as it is a reusable connection

            var writeException: Throwable? = null
            try {
                out.write(endChunk)
            } catch (e: Throwable) {
                writeException = e
            } finally {
                if (writeException == null) {
                    out.flush()
                } else {
                    try {
                        out.flush()
                    } catch (flushException: Throwable) {
                        if (flushException != writeException) {
                            flushException.addSuppressed(writeException)
                        }
                        throw flushException
                    }
                }
            }


        }
    }
}

internal class FixedSizeOutputStream(private val declaredLen: Long, private val out: OutputStream) : OutputStream() {
    private val isClosed = AtomicBoolean(false)
    private var bytesWritten = 0L
    override fun write(b: Int) {
        bytesWritten++
        throwIfOver()
        out.write(b)
    }

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0) {
            bytesWritten += len
            throwIfOver()
            out.write(b, off, len)
        }
    }

    private fun throwIfOver() {
        if (bytesWritten > declaredLen) {
            throw HttpException(
                HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Fixed size body size of $declaredLen exceeded"
            )
        }
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            // don't actually close the underlying as it is a reusable connection
            if (bytesWritten != declaredLen) {
                throw HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Fixed size body expected $declaredLen bytes but had $bytesWritten written")
            }
            out.flush()
        }
    }
}