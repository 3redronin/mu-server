package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class WebSocketHandlerTest {

    @Test
    public void acceptKeysCanBeCreated() throws Exception {
        assertThat(WebSocketHandler.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="), equalTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
    }

}