package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class Http2ConfigTest {

    @Test
    public void toBuilderWorks() {
        var originalBuilder = Http2ConfigBuilder.http2Config()
            .withEnabled(false)
            .withMaxHeaderTableSize(1)
            .withMaxConcurrentStreams(2)
            .withMaxFrameSize(20000)
            .withMaxHeaderListSize(4)
            .withInitialWindowSize(50000);
        var originalConfig = originalBuilder.build();

        assertThat(originalConfig.enabled(), equalTo(false));
        assertThat(originalConfig.maxHeaderTableSize(), equalTo(1));
        assertThat(originalConfig.maxConcurrentStreams(), equalTo(2));
        assertThat(originalConfig.maxFrameSize(), equalTo(20000));
        assertThat(originalConfig.maxHeaderListSize(), equalTo(4));
        assertThat(originalConfig.initialWindowSize(), equalTo(50000));

        var remadeBuilder = originalConfig.toBuilder();
        assertThat(remadeBuilder, equalTo(originalBuilder));
        assertThat(remadeBuilder.build(), equalTo(originalConfig));
    }

}