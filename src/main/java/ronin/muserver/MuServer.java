package ronin.muserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

import java.util.List;

public class MuServer {

	private int httpPort;
	private final List<AsyncMuHandler> handlers;
	private NioEventLoopGroup bossGroup;
	private NioEventLoopGroup workerGroup;
	private Channel channel;

	MuServer(int httpPort, List<AsyncMuHandler> handlers) {
		this.httpPort = httpPort;
		this.handlers = handlers;
	}

	public void start() throws InterruptedException {
		if (bossGroup != null) {
			throw new IllegalStateException("Start already called");
		}
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		ServerBootstrap b = new ServerBootstrap();
		b.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
//				.handler(new LoggingHandler(LogLevel.INFO))
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel socketChannel) throws Exception {
						ChannelPipeline p = socketChannel.pipeline();
						p.addLast(new HttpRequestDecoder());
						p.addLast(new HttpResponseEncoder());
						p.addLast(new MuServerHandler(handlers));
					}
				});
		channel = b.bind(httpPort).sync().channel();
	}

	public void stop() throws InterruptedException {
		channel.close().sync();
		bossGroup.shutdownGracefully();
		workerGroup.shutdownGracefully();
	}

}
