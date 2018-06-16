package io.muserver;

import java.util.concurrent.Future;

/**
 * @deprecated This interface is no longer used. Instead call {@link MuRequest#handleAsync()} from a standard Mu Handler.
 */
@Deprecated
public class AsyncContext {
	public final MuRequest request;
	public final MuResponse response;
	public Object state;
	GrowableByteBufferInputStream requestBody;

	public AsyncContext(MuRequest request, MuResponse response) {
		this.request = request;
		this.response = response;
	}

	public Future<Void> complete() {
		return ((NettyResponseAdaptor)response).complete();
	}
}
