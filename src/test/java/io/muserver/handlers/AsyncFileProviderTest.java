package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Date;

import static io.muserver.Mutils.urlEncode;
import static io.muserver.handlers.ResourceHandlerBuilder.fileHandler;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class AsyncFileProviderTest {

    private MuServer server;
    public static final File BIG_FILE_DIR = new File("src/test/big-files");

    // Skipping large files is a good idea if the test is too slow
    private static final boolean SKIP_LARGE_FILES = true;

    @Test
    public void canReadFilesFromFileSystem() throws Exception {

        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(fileHandler(BIG_FILE_DIR))
            .start();

        File[] files = BIG_FILE_DIR.listFiles(File::isFile);
        assertThat(files.length, Matchers.greaterThanOrEqualTo(2));
        for (File file : files) {
            boolean isLarge = file.length() > 10000000L;
            if (SKIP_LARGE_FILES && isLarge) {
                continue;
            }
            URI downloadUri = server.uri().resolve("/" + urlEncode(file.getName()));

            try (Response resp = call(request(downloadUri))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.headers().toString(), resp.headers().get("content-length"), equalTo(String.valueOf(file.length())));
                assertThat(resp.headers().get("last-modified"), equalTo(Mutils.toHttpDate(new Date(file.lastModified()))));
                assertThat(isEqual(new FileInputStream(file), resp.body().byteStream()), is(true));
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

                int n1;
                n1 = ch1.read(buf1);
                int n2;
                n2 = ch2.read(buf2);
                if (n1 == -1 && n2 == -1) {
                    return true;
                }

                if (n1 == -1 || n2 == -1) {
                    continue;
                }

                buf1.flip();
                buf2.flip();

                for (int i = 0; i < Math.min(n1, n2); i++)
                    if (buf1.get() != buf2.get()) {
                        return false;
                    }

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
        MuAssert.stopAndCheck(server);
    }
}