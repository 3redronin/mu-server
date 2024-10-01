package io.muserver.rest;

import jakarta.ws.rs.core.CacheControl;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CacheControlHeaderDelegateTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void canRoundTrip() {
        assertThat(CacheControl.valueOf("no-cache").toString(), is("no-cache"));
        assertThat(CacheControl.valueOf("private, no-cache,no-store,  no-transform , must-revalidate   ,   proxy-revalidate ").toString(),
            is("private, no-cache, no-store, no-transform, must-revalidate, proxy-revalidate"));
    }

    @Test
    public void nameValuesCanBeUsed() {
        assertThat(CacheControl.valueOf("max-age=31536000").toString(), is("max-age=31536000"));
        assertThat(CacheControl.valueOf("max-age=1000,s-maxage=31536000,no-store").toString(), is("no-store, max-age=1000, s-maxage=31536000"));
    }

    @Test
    public void extensionsCanBeUsed() {
        assertThat(CacheControl.valueOf("immutable").toString(), is("immutable"));
        assertThat(CacheControl.valueOf("stale-while-revalidate=13").toString(), is("stale-while-revalidate=13"));
        assertThat(CacheControl.valueOf("max-stale").toString(), is("max-stale"));
        assertThat(CacheControl.valueOf("max-stale=15").toString(), is("max-stale=15"));
    }

    @Test
    public void allCanBeMixed() {
        assertThat(CacheControl.valueOf("stale-while-revalidate=13, max-age=30, stale-if-error=10, private, no-cache,no-store,  no-transform , must-revalidate   ,   proxy-revalidate ").toString(),
            is("private, no-cache, no-store, no-transform, must-revalidate, proxy-revalidate, max-age=30, stale-if-error=10, stale-while-revalidate=13"));
    }

}