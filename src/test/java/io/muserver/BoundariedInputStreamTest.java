package io.muserver;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class BoundariedInputStreamTest {

    @Test
    public void stopsWhenItHitsBoundary() throws IOException {
        String input = "blah blah this is ignored preamble\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 7\r\n" +
            "\r\n" +
            "Wor\r\nld\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
            "\r\n" +
            "你好 the value / with / stuff\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n";

        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(UTF_8));

        BoundariedInputStream bis = new BoundariedInputStream(bais, "--2fe110ee-3c8a-480b-a07b-32d777205a76");
        assertThat(asString(bis), is("blah blah this is ignored preamble\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis),
            is("\r\n" +
                "Content-Disposition: form-data; name=\"Hello\"\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "Wor\r\nld\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis),
            is("\r\n" +
                "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
                "\r\n" +
                "你好 the value / with / stuff\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis), is("--\r\n"));

        bis = bis.continueNext();
        assertThat(bis, is(nullValue()));
    }

    @Test
    public void bytesCanComeInOneByOne() throws IOException {

        String input = "blah blah this is ignored preamble\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 7\r\n" +
            "\r\n" +
            "Wor\r\nld\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
            "\r\n" +
            "你好 the value / with / stuff\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n";

        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(UTF_8)) {
            public synchronized int read(byte[] b, int off, int len) {
                int read = this.read();
                if (read == -1) {
                    return -1;
                } else {
                    b[off] = (byte) read;
                    return 1;
                }
            }
        };

        BoundariedInputStream bis = new BoundariedInputStream(bais, "--2fe110ee-3c8a-480b-a07b-32d777205a76");
        assertThat(asString(bis), is("blah blah this is ignored preamble\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis),
            is("\r\n" +
                "Content-Disposition: form-data; name=\"Hello\"\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "Wor\r\nld\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis),
            is("\r\n" +
                "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
                "\r\n" +
                "你好 the value / with / stuff\r\n"));

        bis = bis.continueNext();
        assertThat(asString(bis), is("--\r\n"));

        bis = bis.continueNext();
        assertThat(bis, is(nullValue()));
    }

    @Test
    public void theBoundaryCanBeChanged() throws IOException {
        String input = "blah blah this is ignored preamble\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 7\r\n" +
            "\r\n" +
            "Wor\r\nld\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
            "\r\n" +
            "你好 the value / with / stuff\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(UTF_8));

        BoundariedInputStream outer = new BoundariedInputStream(bais, "\r\n--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n");

        BoundariedInputStream bis = new BoundariedInputStream(outer, "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n");
        assertThat(asString(bis), is("blah blah this is ignored preamble\r\n"));

        bis.changeBoundary("\r\n--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n");
        bis = bis.continueNext();
        assertThat(asString(bis), is("Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 7\r\n" +
            "\r\n" +
            "Wor\r\nld"));
        bis = bis.continueNext();
        assertThat(asString(bis), is("Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
            "\r\n" +
            "你好 the value / with / stuff"));

        bis = bis.continueNext();
        assertThat(bis, is(nullValue()));
    }

    @Test
    public void largeBinaryFilesWork() throws IOException {


        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(("--43c1c131-74dd-4048-abc3-fb668bc8d734\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "World\r\n" +
            "--43c1c131-74dd-4048-abc3-fb668bc8d734\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: 372987\r\n" +
            "\r\n").getBytes(UTF_8));

        try (FileInputStream fis = new FileInputStream(UploadTest.guangzhou)) {
            Mutils.copy(fis, baos, 8192);
        }

        baos.write("\r\n--43c1c131-74dd-4048-abc3-fb668bc8d734--\r\n".getBytes(UTF_8));


        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        BoundariedInputStream outer = new BoundariedInputStream(bais, "\r\n--43c1c131-74dd-4048-abc3-fb668bc8d734--\r\n");

        BoundariedInputStream bis = new BoundariedInputStream(outer, "--43c1c131-74dd-4048-abc3-fb668bc8d734\r\n");
        assertThat(asString(bis), is(""));
        bis = bis.continueNext();

        bis.changeBoundary("\r\n--43c1c131-74dd-4048-abc3-fb668bc8d734\r\n");
        assertThat(asString(bis), is("Content-Disposition: form-data; name=\"Hello\"\r\nContent-Length: 5\r\n\r\nWorld"));

        bis = bis.continueNext();


        String imageAsString = asString(bis);
        assertThat(imageAsString, startsWith("Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: 372987\r\n" +
            "\r\n"));
        assertThat(imageAsString.length(), is(353025));

        bis = bis.continueNext();
        assertThat(bis, is(nullValue()));
    }

    private static String asString(BoundariedInputStream bis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = bis.read(buffer)) > -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toString("UTF-8");
    }

    @Test
    public void indexOfInByteArrays() {
        byte[] target = new byte[]{1, 2, 3, 4, 5, 6};

        assertThat(BoundariedInputStream.indexOf(target, 0, target.length, new byte[]{1, 2}), is(arrayMatch(0, true)));
        assertThat(BoundariedInputStream.indexOf(target, 0, target.length, new byte[]{2, 3}), is(arrayMatch(1, true)));
        assertThat(BoundariedInputStream.indexOf(target, 0, target.length, new byte[]{5, 6}), is(arrayMatch(4, true)));
        assertThat(BoundariedInputStream.indexOf(target, 0, target.length, new byte[]{2, 4}), is(arrayMatch(-1, false)));
        assertThat(BoundariedInputStream.indexOf(target, 0, target.length, new byte[]{5, 6, 7}), is(arrayMatch(4, false)));

    }

    private static BoundariedInputStream.ArrayMatch arrayMatch(int i, boolean b) {
        return new BoundariedInputStream.ArrayMatch(i, b);
    }

}