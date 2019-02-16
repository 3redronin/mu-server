package io.muserver.handlers;

import org.junit.Test;

import java.io.File;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ClasspathResourceProviderTest {

    private static File guangzhou = new File("src/test/resources/sample-static/images/guangzhou.jpeg");

    @Test
    public void itKnowsThingsAboutFiles() {
        ClasspathResourceProvider provider = new ClasspathResourceProvider("/sample-static", "images/guangzhou, china.jpeg");
        assertThat(provider.exists(), is(true));
        assertThat(provider.isDirectory(), is(false));
        assertThat(provider.fileSize(), is(guangzhou.length()));
        assertThat(provider.lastModified(), instanceOf(Date.class)); // last-modified changes on start-up
    }

    @Test
    public void itKnowsThingsAboutDirectories() {
        ClasspathResourceProvider provider = new ClasspathResourceProvider("/sample-static", "images");
        assertThat(provider.exists(), is(true));
        assertThat(provider.isDirectory(), is(true));
        assertThat(provider.fileSize(), is(nullValue()));
        assertThat(provider.lastModified(), instanceOf(Date.class)); // last-modified changes on start-up
    }

}
