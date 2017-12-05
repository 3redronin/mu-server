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
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class MuServer {

	private int httpPort;
	private NioEventLoopGroup bossGroup;
	private NioEventLoopGroup workerGroup;
	private Channel channel;

	MuServer(int httpPort) {
		this.httpPort = httpPort;
	}

	public void start() throws InterruptedException {
		 bossGroup = new NioEventLoopGroup(1);
		 workerGroup = new NioEventLoopGroup();
		      ServerBootstrap b = new ServerBootstrap();
		      b.group(bossGroup, workerGroup)
		       .channel(NioServerSocketChannel.class)
		       .handler(new LoggingHandler(LogLevel.INFO))
		       .childHandler(new ChannelInitializer<SocketChannel>() {
			       @Override
			       protected void initChannel(SocketChannel socketChannel) throws Exception {
				       ChannelPipeline p = socketChannel.pipeline();
				       p.addLast(new HttpRequestDecoder());

				       p.addLast(new HttpResponseEncoder());
				       p.addLast(new MuServerHandler());
			       }
		       } );

		      channel = b.bind(httpPort).sync().channel();


	}

	public void stop() {
		bossGroup.shutdownGracefully();
		workerGroup.shutdownGracefully();

	}

}
