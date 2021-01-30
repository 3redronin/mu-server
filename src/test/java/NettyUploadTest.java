import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class NettyUploadTest {

    @Test
    @Ignore("It can't! See https://github.com/netty/netty/issues/10973")
    public void itCanProcessLargeFiles() throws Exception {

        int fileSize = 100_000_000; // set Xmx to a number lower than this and it crashes
        int bytesPerChunk = 1_000_000;


        String prefix = "--861fbeab-cd20-470c-9609-d40a0f704466\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\n" +
            "Content-Type: image/jpeg\n" +
            "Content-Length: " + fileSize + "\n" +
            "\n";

        String suffix = "\n" +
            "--861fbeab-cd20-470c-9609-d40a0f704466--\n";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        request.headers().set("content-length", prefix.length() + fileSize + suffix.length());
        HttpDataFactory factory = new DefaultHttpDataFactory(true);
        HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, request);
        decoder.offer(new DefaultHttpContent(Unpooled.wrappedBuffer(prefix.getBytes(StandardCharsets.UTF_8))));

        byte[] body = new byte[bytesPerChunk];
        Arrays.fill(body, (byte)1);
        for (int i = 0; i < fileSize / bytesPerChunk; i++) {
            ByteBuf content = Unpooled.wrappedBuffer(body, 0, bytesPerChunk);
            DefaultHttpContent httpContent = new DefaultHttpContent(content);
            decoder.offer(httpContent); // OutOfMemoryHere
            httpContent.release();
        }

        decoder.offer(new DefaultHttpContent(Unpooled.wrappedBuffer(suffix.getBytes(StandardCharsets.UTF_8))));
        decoder.offer(new DefaultLastHttpContent());
        FileUpload data = (FileUpload) decoder.getBodyHttpDatas().get(0);
        assertThat((int)data.length(), is(fileSize));
        assertThat(data.get().length, is(fileSize));

        factory.cleanAllHttpData();

    }

}
