package io.muserver;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class MuFlowControlHandlerTest {

    @Test
    public void readCompleteDoesNotConsumeAnOutstandingRead() throws Exception {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead(false);
        channel.register();

        channel.read();
        channel.pipeline().fireChannelReadComplete();
        channel.pipeline().fireChannelRead("the requested message");

        assertThat(channel.readInbound(), is("the requested message"));
        assertThat(channel.readInbound(), nullValue());
        assertThat(channel.finishAndReleaseAll(), is(false));
    }

    @Test
    public void eachReadReleasesOnlyOneQueuedMessage() throws Exception {
        EmbeddedChannel channel = newChannel();
        channel.config().setAutoRead(false);
        channel.register();
        channel.pipeline().fireChannelRead("one");
        channel.pipeline().fireChannelRead("two");

        assertThat(channel.readInbound(), nullValue());
        channel.read();
        assertThat(channel.readInbound(), is("one"));
        assertThat(channel.readInbound(), nullValue());
        channel.read();
        assertThat(channel.readInbound(), is("two"));
        assertThat(channel.finishAndReleaseAll(), is(false));
    }

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(false, false, new MuFlowControlHandler(), new ChannelInboundHandlerAdapter());
    }
}
