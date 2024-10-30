package io.muserver.rest;

import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class RuntimeDelegateTest {

    @Test
    public void theDelegateIsSetInServiceLoaderFormat() {
        RuntimeDelegate instance = RuntimeDelegate.getInstance();
        assertThat(instance.getClass().getSimpleName(), equalTo("MuRuntimeDelegate"));
    }
}
