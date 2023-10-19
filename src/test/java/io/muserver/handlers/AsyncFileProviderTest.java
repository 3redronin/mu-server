package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.*;
import java.net.URI;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Mutils.urlEncode;
import static io.muserver.handlers.ResourceHandlerBuilder.fileHandler;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class AsyncFileProviderTest {

    private MuServer server;
    public static final File BIG_FILE_DIR = new File("src/test/big-files");

    // Skipping large files is a good idea if the test is too slow
    private static final boolean SKIP_LARGE_FILES = false;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void canReadFilesFromFileSystem(String type) throws Exception {

        server = ServerUtils.httpsServerForTest(type)
            .withGzipEnabled(false)
            .addHandler(fileHandler(BIG_FILE_DIR))
            .start();

        File[] files = BIG_FILE_DIR.listFiles(File::isFile);
        assertThat(files.length, greaterThanOrEqualTo(2));
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

    @Test
    public void canRespondWithFilesThatAreBeingWrittenTo() throws Exception {
        File dir = new File("target/test-data/file-provider");
        assertThat(dir.isDirectory() || dir.mkdirs(), is(true));
        File exampleFile = new File(dir, UUID.randomUUID() + ".txt");
        try (FileWriter writer = new FileWriter(exampleFile)) {

            AtomicBoolean running = new AtomicBoolean(false);
            AtomicReference<Exception> writeException = new AtomicReference<>(null);
            Thread writerThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        writer.write("More text " + System.currentTimeMillis() + "\n");
                        writer.flush();
                    } catch (Exception e) {
                        writeException.set(e);
                    }
                }
            });


            String prefix = StringUtils.randomAsciiStringOfLength(32000);
            writer.write(prefix);
            writer.flush();
            server = ServerUtils.httpsServerForTest()
                .withGzipEnabled(false)
                .addHandler((request, response) -> {
                    running.set(true);
                    writerThread.start();
                    return false;
                })
                .addHandler(fileHandler(dir))
                .addResponseCompleteListener(info -> running.set(false))
                .start();

            try (Response resp = call(request(server.uri().resolve("/" + exampleFile.getName())))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.headers().toString(), Long.parseLong(resp.headers().get("content-length")), greaterThanOrEqualTo((long) prefix.length()));
                String body = resp.body().string();
                assertThat(body, startsWith(prefix));
            }
            assertThat(writeException.get(), is(nullValue()));

        } finally {
            exampleFile.delete();
        }
    }

    private static boolean isEqual(InputStream i1, InputStream i2) throws IOException {
        try (var b1 = new BufferedInputStream(i1);
            var b2 = new BufferedInputStream(i2)) {
            while (true) {
                int fr = b1.read();
                int tr = b2.read();
                if (fr != tr)
                    return false;
                if (fr == -1)
                    return true;
            }
        }
    }

    @AfterEach
    public void stop() {
        MuAssert.stopAndCheck(server);
    }
}