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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MuServerBuilder {
	private static final int LENGTH_OF_METHOD_AND_PROTOCOL = 17; // e.g. "OPTIONS HTTP/1.1 "
	public int httpPort = 0;
	public int maxHeadersSize = 8192;
	public int maxUrlSize = 8192 - LENGTH_OF_METHOD_AND_PROTOCOL;
	private List<AsyncMuHandler> asyncHandlers = new ArrayList<>();
	private List<MuHandler> handlers = new ArrayList<>();

	public MuServerBuilder withHttpConnection(int port) {
		this.httpPort = port;
		return this;
	}
	public MuServerBuilder withMaxHeadersSize(int size) {
		this.maxHeadersSize = size;
		return this;
	}
	public MuServerBuilder withMaxUrlSize(int size) {
		this.maxUrlSize = size;
		return this;
	}

	public MuServerBuilder addAsyncHandler(AsyncMuHandler handler) {
		asyncHandlers.add(handler);
		return this;
	}

	public MuServerBuilder addHandler(MuHandler handler) {
		handlers.add(handler);
		return this;
	}

	public MuServerBuilder addHandler(HttpMethod method, String pathRegex, MuHandler handler) {
		return addHandler(Routes.route(method, pathRegex, handler));
	}

	public MuServer start() {
		if (!handlers.isEmpty()) {
			asyncHandlers.add(new SyncHandlerAdapter(handlers));
		}
		try {
			NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
			NioEventLoopGroup workerGroup = new NioEventLoopGroup();
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel socketChannel) throws Exception {
							ChannelPipeline p = socketChannel.pipeline();
							p.addLast(new HttpRequestDecoder(maxUrlSize + LENGTH_OF_METHOD_AND_PROTOCOL,
									maxHeadersSize, 8192));
							p.addLast(new HttpResponseEncoder());
							p.addLast(new MuServerHandler(asyncHandlers));
						}
					});
			Channel channel = b.bind(httpPort).sync().channel();
			InetSocketAddress a = (InetSocketAddress) channel.localAddress();
			URI uri = URI.create("http://localhost:" + a.getPort());
			URL url = uri.toURL();

			Runnable shutdown = () -> {
				try {
					channel.close().sync();
					bossGroup.shutdownGracefully();
					workerGroup.shutdownGracefully();
				} catch (Exception e) {
					System.out.println("Error while shutting down. Will ignore. Error was: " + e.getMessage());
				}
			};
			return new MuServer(uri, url, shutdown);

		} catch (Exception ex) {
			throw new MuException("Error while starting server", ex);
		}

	}

	public static MuServerBuilder muServer() {
		return new MuServerBuilder();
	}
}
