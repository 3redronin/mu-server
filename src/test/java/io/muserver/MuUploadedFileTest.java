package io.muserver;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import org.junit.Test;

import java.io.*;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class MuUploadedFileTest {

    @Test
    public void canGetInfoWhenItIsInMemory() throws IOException {
        MemoryFileUpload fileUpload = new MemoryFileUpload("something", "C:\\data\\my-file.jpg", "image/jpeg", "Chunked", UTF_8, 1L);
        fileUpload.setContent(UploadTest.guangzhou);
        MuUploadedFile muf = new MuUploadedFile(fileUpload);

        assertThat(muf.filename(), equalTo("my-file.jpg"));
        assertThat(muf.extension(), equalTo("jpg"));
        assertThat(muf.size(), equalTo(372987L));
        assertThat(muf.contentType(), equalTo("image/jpeg"));
        assertThat(muf.asBytes().length, is(372987));

        File file = muf.asFile();
        assertThat(file.isFile(), is(true));
        assertThat(file.length(), is(372987L));
    }

    @Test
    public void canGetAsString() throws IOException {
        DiskFileUpload fileUpload = new DiskFileUpload("something", "my-file", "text/plain", "Chunked", UTF_8, 14L);
        fileUpload.setContent(Unpooled.copiedBuffer("Hello, world", UTF_8));

        MuUploadedFile muf = new MuUploadedFile(fileUpload);

        assertThat(muf.filename(), equalTo("my-file"));
        assertThat(muf.extension(), equalTo(""));
        assertThat(muf.size(), equalTo(12L));
        assertThat(muf.contentType(), equalTo("text/plain"));
        assertThat(muf.asBytes(), equalTo("Hello, world".getBytes(UTF_8)));
        assertThat(muf.asString(), equalTo("Hello, world"));

        File file = muf.asFile();
        assertThat(file.isFile(), is(true));
        assertThat(file.length(), is(12L));

        File dest = new File("target/testoutput/sample" + UUID.randomUUID() + ".txt");
        muf.saveTo(dest);
        try (FileInputStream destStream = new FileInputStream(dest)) {
            byte[] destBytes = Mutils.toByteArray(destStream, 1024);
            assertThat(new String(destBytes, UTF_8), equalTo("Hello, world"));
        }

        assertThat(file.isFile(), is(false)); // because after saving, it's moved
        assertThat(muf.asFile().isFile(), is(true));
    }

}