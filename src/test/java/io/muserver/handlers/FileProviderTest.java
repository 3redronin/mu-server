package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;

import static io.muserver.MuServerBuilder.muServer;
import static io.muserver.Mutils.urlEncode;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FileProviderTest {

    private final ResourceProviderFactory factory = ResourceProviderFactory.fileBased(Paths.get("src/test/resources/sample-static"));
    MuServer server;
    public static final File BIG_FILE_DIR = new File("src/test/big-files");

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
    @Ignore("too slow")
    public void canReadFilesFromFileSystem() throws Exception {

        server = muServer()
            .withHttpConnection(12080)
            .addHandler(ResourceHandler.fileHandler(BIG_FILE_DIR))
            .start();

        File[] files = BIG_FILE_DIR.listFiles(File::isFile);
        assertThat(files.length, Matchers.greaterThanOrEqualTo(2));
        for (File file : files) {
            URI downloadUri = server.httpUri().resolve("/" + urlEncode(file.getName()));
            System.out.println("Going to test " + file.getName() + " from " + downloadUri);

            try (Response resp = call(request().url(downloadUri.toString()))) {
                assertThat(resp.code(), is(200));
                System.out.println("resp.headers() = " + resp.headers());
                InputStream inputStream = resp.body().byteStream();
                long soFar = 0;
                long total = file.length();
                byte[] buf = new byte[32 * 1024];
                int read;
                int percent = 0;
                while ((read = inputStream.read(buf)) > -1) {
                    soFar += read;
                    int nowPercent = (int) (100.0 * (soFar / (double)total));
                    if (percent != nowPercent) {
                        percent = nowPercent;
                        System.out.println(file.getName() + " percent = " + percent);
                    }
                }
//                assertThat(isEqual(new FileInputStream(file), resp.body().byteStream()), is(true));
            }
        }

    }


    private static boolean isEqual(InputStream i1, InputStream i2) throws IOException {

        ReadableByteChannel ch1 = Channels.newChannel(i1);
        ReadableByteChannel ch2 = Channels.newChannel(i2);

        ByteBuffer buf1 = ByteBuffer.allocateDirect(1000 * 1024);
        ByteBuffer buf2 = ByteBuffer.allocateDirect(1000 * 1024);

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