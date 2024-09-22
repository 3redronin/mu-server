package io.muserver;

import org.junit.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class MuUploadedFileTest {

    @Test
    public void canGetAsString() throws IOException {
        var path = Path.of("src", "test", "resources", "sample-static", "a, tricky - dir Name", "ooh ah", "a, tricket - file name.txt");
        assertThat(path.toAbsolutePath().toString(), Files.isRegularFile(path), equalTo(true));

        MuUploadedFile2 muf = new MuUploadedFile2(path, "text/plain", "a, tricket - file name.txt");

        assertThat(muf.filename(), equalTo("a, tricket - file name.txt"));
        assertThat(muf.extension(), equalTo("txt"));
        assertThat(muf.size(), equalTo(34L));
        assertThat(muf.contentType(), equalTo("text/plain"));
        assertThat(muf.asBytes(), equalTo("spaces and stuff in the file names".getBytes(UTF_8)));
        assertThat(muf.asString(), equalTo("spaces and stuff in the file names"));

        File file = muf.asFile();
        assertThat(file.isFile(), is(true));
        assertThat(file.length(), is(34L));

        File dest = new File("target/testoutput/sample" + UUID.randomUUID() + ".txt");
        muf.saveTo(dest);
        try (FileInputStream destStream = new FileInputStream(dest)) {
            byte[] destBytes = Mutils.toByteArray(destStream, 1024);
            assertThat(new String(destBytes, UTF_8), equalTo("spaces and stuff in the file names"));
        }

        assertThat(file.isFile(), is(false)); // because after saving, it's moved
        assertThat(muf.asFile().isFile(), is(true));
    }

}