package io.muserver.handlers;

import org.junit.Test;

import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ResourceProviderTest {


    private final ResourceProviderFactory classpathBased = ResourceProviderFactory.classpathBased("/sample-static");
    private final ResourceProviderFactory fileBased = ResourceProviderFactory.fileBased(Paths.get("src/test/resources/sample-static"));


    @Test
    public void fileExistenceCanBeFoundForClasspath() {
        fileExistenceCanBeFound(classpathBased);
    }
    @Test
    public void fileExistenceCanBeFoundForFileBased() {
        fileExistenceCanBeFound(fileBased);
    }
    private static void fileExistenceCanBeFound(ResourceProviderFactory factory) {
        assertThat(factory.get("/no-valid-file").exists(), is(false));
        assertThat(factory.get("/no-valid-file.txt").exists(), is(false));
        assertThat(factory.get("/index.html").exists(), is(true));
        assertThat(factory.get("/index.html").isDirectory(), is(false));
        assertThat(factory.get("./index.html").exists(), is(true));
        assertThat(factory.get("index.html").exists(), is(true));
        assertThat(factory.get("images/guangzhou.jpeg").exists(), is(true));
    }

    @Test
    public void directoriesAreDetectedFromClasspath() {
        directoriesAreDetected(classpathBased);
    }
    @Test
    public void directoriesAreDetectedFromFile() {
        directoriesAreDetected(fileBased);
    }
    private static void directoriesAreDetected(ResourceProviderFactory factory) {
        assertThat(factory.get("/images/").isDirectory(), is(true));
        assertThat(factory.get("/images").isDirectory(), is(true));
        assertThat(factory.get("/images/").exists(), is(true));
        assertThat(factory.get("/images").exists(), is(true));
    }


    @Test
    public void fileSizesAreKnownFromClasspath() {
        fileSizesAreKnown(classpathBased);
    }
    @Test
    public void fileSizesAreKnownFromFile() {
        fileSizesAreKnown(fileBased);
    }
    private static void fileSizesAreKnown(ResourceProviderFactory factory) {
        assertThat(factory.get("images/guangzhou.jpeg").fileSize(), is(372987L));
    }

}