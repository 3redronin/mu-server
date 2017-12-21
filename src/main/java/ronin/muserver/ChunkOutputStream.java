/************************************************************************
 Copyright 2013 Tony Bussieres
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 *************************************************************************/


// Taken with some adaption from https://gist.github.com/codingtony/6564901
package ronin.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;

import java.io.IOException;
import java.io.OutputStream;
/**
 * Class to help application that are built to write to an
 * OutputStream to chunk the content
 *
 * <pre>
 * {@code
DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
HttpHeaders.setTransferEncodingChunked(response);
response.headers().set(CONTENT_TYPE, "application/octet-stream");
//other headers
ctx.write(response);
// code of the application that use the ChunkOutputStream
// Don't forget to close the ChunkOutputStream after use!
ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
}
 </pre>

 * @author tbussier
 *
 */
class ChunkOutputStream extends OutputStream {
    final ByteBuf buffer;
    final ChannelHandlerContext ctx;

    ChunkOutputStream(ChannelHandlerContext ctx, int chunksize) {
        if (chunksize < 1) {
            throw new IllegalArgumentException("Chunk size must be at least 1");
        }
        this.buffer = Unpooled.buffer(0, chunksize);
        this.ctx = ctx;
    }

    @Override
    public void write(int b) throws IOException {
        if (buffer.maxWritableBytes() < 1) {
            flush();
        }
        buffer.writeByte(b);
    }

    @Override
    public void close() throws IOException {
        flush();
        super.close();
    }


    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int dataLengthLeftToWrite = len;
        int dataToWriteOffset = off;
        int spaceLeftInCurrentChunk;
        while ((spaceLeftInCurrentChunk = buffer.maxWritableBytes()) < dataLengthLeftToWrite) {
            buffer.writeBytes(b, dataToWriteOffset, spaceLeftInCurrentChunk);
            dataToWriteOffset = dataToWriteOffset + spaceLeftInCurrentChunk;
            dataLengthLeftToWrite = dataLengthLeftToWrite - spaceLeftInCurrentChunk;
            flush();
        }
        if (dataLengthLeftToWrite > 0) {
            buffer.writeBytes(b, dataToWriteOffset, dataLengthLeftToWrite);
        }
        if (buffer.maxWritableBytes() < 1) {
            flush();
        }
    }

    @Override
    public void flush() throws IOException {
        ctx.writeAndFlush(new DefaultHttpContent(buffer.copy()));
        buffer.clear();
        super.flush();
    }

}