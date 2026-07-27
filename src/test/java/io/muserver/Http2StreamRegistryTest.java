package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2StreamRegistryTest {

    @Test
    void rejectedRequestBodyHasOneExplicitIdentity() {
        var registry = new Http2StreamRegistry();

        registry.registerRejectedRequestBody(1);

        var registered = registry.lookup(1);
        assertThat(registered.applicationStream(), nullValue());
        assertThat(registered.rejectedRequestBody(), equalTo(true));
        assertThat(registry.concurrentStreamCount(), equalTo(1L));
        assertThat(registry.isEmpty(), equalTo(false));

        assertThat(registry.removeRejectedRequestBody(1), equalTo(true));
        assertThat(registry.removeRejectedRequestBody(1), equalTo(false));
        assertThat(registry.lookup(1).rejectedRequestBody(), equalTo(false));
        assertThat(registry.isEmpty(), equalTo(true));
    }

    @Test
    void duplicateAndInvalidRejectedIdentitiesAreRejected() {
        var registry = new Http2StreamRegistry();
        registry.registerRejectedRequestBody(1);

        assertThrows(
            IllegalStateException.class,
            () -> registry.registerRejectedRequestBody(1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.registerRejectedRequestBody(0)
        );
    }
}
