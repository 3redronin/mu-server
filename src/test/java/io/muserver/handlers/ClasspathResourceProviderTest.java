package io.muserver.handlers;

import org.hamcrest.MatcherAssert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class ClasspathResourceProviderTest {


    private final ResourceProviderFactory factory = ResourceProviderFactory.classpathBased("/sample-static");

    @Test
    public void fileExistenceCanBeFound() {
        assertThat(factory.get("/no-valid-file").exists(), is(false));
        assertThat(factory.get("/no-valid-file.txt").exists(), is(false));
//        assertThat(factory.get("/images").exists(), is(false));
        assertThat(factory.get("/images/").exists(), is(false));
        assertThat(factory.get("/index.html").exists(), is(true));
        assertThat(factory.get("./index.html").exists(), is(true));
        assertThat(factory.get("index.html").exists(), is(true));
        assertThat(factory.get("images/guangzhou.jpeg").exists(), is(true));
    }

    @Test
    public void pathsMustBeDescendantsOfBase() {
        assertThat(factory.get("../something.txt").exists(), is(false));
    }

    @Test
    public void fileSizesAreKnown() {
        assertThat(factory.get("images/guangzhou.jpeg").fileSize(), is(372987L));
    }

    @Test
    public void fileContentsCanBeGot() throws IOException {
        ResourceProvider provider = factory.get("images/guangzhou.jpeg");
        ByteArrayOutputStream out = new ByteArrayOutputStream(372987);
        provider.writeTo(out, 32 * 1024);
        MatcherAssert.assertThat(FileProviderTest.toHex(out.toByteArray()), equalTo(FileProviderTest.toHex(Files.readAllBytes(Paths.get("src/test/resources/sample-static/images/guangzhou.jpeg")))));
    }

}