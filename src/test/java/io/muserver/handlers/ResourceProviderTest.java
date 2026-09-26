package io.muserver.handlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class ResourceProviderTest {

    @Test
    public void pathsOutsideTheBaseHaveNoResourceOrMetadata(@TempDir Path root) throws Exception {
        Path base = Files.createDirectory(root.resolve("public"));
        Files.writeString(root.resolve("secret.txt"), "secret");
        Files.writeString(base.resolve("__mu_invalid_resource_path__"), "ordinary file");
        ResourceProviderFactory factory = ResourceProviderFactory.fileBased(base);
        for (String path : new String[]{"../secret.txt", "/../secret.txt", "../", "../public-other"}) {
            ResourceProvider provider = factory.get(path);
            assertThat(path, provider.exists(), is(false));
            assertThat(path, provider.isDirectory(), is(false));
            assertThat(path, provider.fileSize(), nullValue());
            assertThat(path, provider.lastModified(), nullValue());
            try (var files = provider.listFiles()) {
                assertThat(path, files.count(), is(0L));
            }
        }
        assertThat(factory.get("__mu_invalid_resource_path__").exists(), is(true));
    }


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
