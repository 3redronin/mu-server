package io.muserver.handlers;

import org.junit.Test;

import java.io.File;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ClasspathResourceProviderTest {

    private static File guangzhou = new File("src/test/resources/sample-static/images/guangzhou.jpeg");
    private ResourceProviderFactory factory = ResourceProviderFactory.classpathBased("/sample-static");

    @Test
    public void itKnowsThingsAboutFiles() {
        ResourceProvider provider = factory.get("/images/guangzhou, china.jpeg");
        assertThat(provider.exists(), is(true));
        assertThat(provider.isDirectory(), is(false));
        assertThat(provider.fileSize(), is(guangzhou.length()));
        assertThat(provider.lastModified(), instanceOf(Date.class)); // last-modified changes on start-up
    }

    @Test
    public void itKnowsThingsAboutDirectories() {
        ResourceProvider provider = factory.get("/images");
        assertThat(provider.exists(), is(true));
        assertThat(provider.isDirectory(), is(true));
        assertThat(provider.fileSize(), is(nullValue()));
        assertThat(provider.lastModified(), instanceOf(Date.class)); // last-modified changes on start-up
    }

    @Test
    public void nonExistantPathsReturnNotExists() {
        ResourceProviderFactory factory = ResourceProviderFactory.classpathBased("/this-does-not-exist");
        ResourceProvider images = factory.get("/images");
        assertThat(images.exists(), is(false));
    }

}
