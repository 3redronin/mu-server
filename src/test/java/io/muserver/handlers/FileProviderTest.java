package io.muserver.handlers;

import io.muserver.MuServer;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.Mutils.urlEncode;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FileProviderTest {

    private final ResourceProviderFactory factory = ResourceProviderFactory.fileBased(Paths.get("src/test/resources/sample-static"));
    MuServer server;

    @Test
    public void fileExistenceCanBeFound() {
        assertThat(factory.get("/no-valid-file").exists(), is(false));
        assertThat(factory.get("/images").exists(), is(false));
        assertThat(factory.get("/images/").exists(), is(false));
        assertThat(factory.get("./index.html").exists(), is(true));
        assertThat(factory.get("index.html").exists(), is(true));
    }

    @Test
    @Ignore("This fails... should this return false or an error though?")
    public void pathsMustBeDescendantsOfBase() {
        assertThat(factory.get("../something.txt").exists(), is(false));
    }

    @Test
    public void fileSizesCanBeFound() {
        assertThat(factory.get("images/guangzhou.jpeg").fileSize(), is(372987L));
    }

    @Test
    @Ignore("Not working yet")
    public void canReadFilesFromFileSystem() throws IOException {
        File bigFileDir = new File("src/test/big-files");

        server = httpsServer()
            .addHandler(ResourceHandler.fileHandler(bigFileDir))
            .start();

        File[] files = bigFileDir.listFiles(File::isFile);
        assertThat(files.length, Matchers.greaterThanOrEqualTo(2));
        for (File file : files) {
            try (Response resp = call(request().url(server.uri().resolve("/" + urlEncode(file.getName())).toString()))) {
                assertThat(resp.code(), is(200));
                assertThat(isEqual(new FileInputStream(file), resp.body().byteStream()), is(true));
            }

        }

    }

    private static boolean isEqual(InputStream i1, InputStream i2) throws IOException {

        ReadableByteChannel ch1 = Channels.newChannel(i1);
        ReadableByteChannel ch2 = Channels.newChannel(i2);

        ByteBuffer buf1 = ByteBuffer.allocateDirect(1024);
        ByteBuffer buf2 = ByteBuffer.allocateDirect(1024);

        try {
            while (true) {

                int n1 = ch1.read(buf1);
                int n2 = ch2.read(buf2);

                if (n1 == -1 || n2 == -1) return n1 == n2;

                buf1.flip();
                buf2.flip();

                for (int i = 0; i < Math.min(n1, n2); i++)
                    if (buf1.get() != buf2.get())
                        return false;

                buf1.compact();
                buf2.compact();
            }

        } finally {
            i1.close();
            i2.close();
        }
    }
    @After
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}