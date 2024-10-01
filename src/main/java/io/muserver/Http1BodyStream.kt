package io.muserver

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class Http1BodyStream(private val parser: Http1MessageReader, private val maxBodySize: Long) : InputStream() {

    private var bb : ByteBuffer? = null
    private var lastBitReceived = false
    private var bytesReceived = 0L

    private val eof = AtomicBoolean(false)

    override fun read(): Int {
        blockUntilData()
        if (eof.get()) return -1
        return bb!!.get().toInt()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (off < 0) throw IndexOutOfBoundsException("Negative offset")
        if (len < 0) throw IndexOutOfBoundsException("Negative length")
        if (len > b.size - off) throw IndexOutOfBoundsException("Length too long")
        blockUntilData()
        if (eof.get()) return -1
        val bit = bb!!
        val toWrite = minOf(len, bit.remaining())
        if (toWrite > 0) {
            bit.get(b, off, toWrite)
        }
        return toWrite
    }

    private fun blockUntilData() {
        if (!eof.get()) {
            var ready = false
            while (!ready) {
                val lastBody = bb

                // If no body has been received, or the last bit has been consumed...
                if (lastBody == null || !lastBody.hasRemaining()) {
                    if (lastBitReceived) {
                        // ...we expect no more body bits, so it's an EOF
                        eof.set(true)
                        ready = true
                    } else {
                        // ...we expect more body, so read the next bit
                        val next = parser.readNext()
                        if (next is MessageBodyBit) {
                            // we have more body data
                            lastBitReceived = next.isLast
                            // this is an empty last-data message, so it is EOF time
                            if (next.isLast && next.length == 0) {
                                eof.set(true)
                                bb = null
                                ready = true
                            } else if (next.length > 0) {
                                bb = ByteBuffer.wrap(next.bytes, next.offset, next.length)
                                bytesReceived += next.length
                                if (bytesReceived > maxBodySize) {
                                    throw HttpException(HttpStatus.CONTENT_TOO_LARGE_413)
                                }
                                ready = true
                            }
                        } else if (next is EndOfBodyBit) {
                            bb = null
                            eof.set(true)
                            ready = true
                        } else {
                            throw IOException("Unexpected message: ${next.javaClass.name}")
                        }
                    }
                } else if (lastBody.hasRemaining()) {
                    ready = true
                }
            }
        }
    }


    override fun skip(n: Long): Long {
        if (eof.get()) return 0L
        if (n <= 0L) return 0L
        var rem = n
        while (rem > 0) {
            blockUntilData()
            val s = minOf(bb!!.remaining().toLong(), rem)
            bb!!.position(bb!!.position() + s.toInt())
            rem -= s
        }
        return n
    }

    override fun available(): Int {
        return bb?.remaining() ?: 0
    }

    /**
     * Discards any remaining bits of this stream and closes the stream.
     * <p>This can be called multiple times</p>
     */
    @Throws(IOException::class)
    override fun close() {
        if (eof.compareAndSet(false, true)) {
            var drained = lastBitReceived
            while (!drained) {
                val last = parser.readNext()
                if (last is MessageBodyBit) {
                    drained = last.isLast
                } else if (last is EndOfBodyBit) {
                    drained = true
                } else {
                    throw IOException("Unexpected message: ${last.javaClass.name}")
                }
            }
        }
    }
}
