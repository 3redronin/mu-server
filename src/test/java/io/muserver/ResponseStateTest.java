package io.muserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ResponseStateTest {

    @Test
    void lateSuccessfulCleanupCannotOverwriteAConnectionFailure() {
        var response = new Http1Response(null, new ByteArrayOutputStream());

        assertThat(response.setState(ResponseState.WRITING_HEADERS), equalTo(true));
        assertThat(response.setState(ResponseState.CLIENT_DISCONNECTED), equalTo(true));

        assertThat(response.setState(ResponseState.FINISHED), equalTo(false));
        assertThat(response.responseState(), equalTo(ResponseState.CLIENT_DISCONNECTED));
    }
}
