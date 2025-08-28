package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

@DisplayName("RFC 9113 6.5 Frame Definitions: SETTINGS")
class RFC9113_6_5_SettingsTest {

    private @Nullable MuServer server;




    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
