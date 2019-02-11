package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.CacheControl;

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

}