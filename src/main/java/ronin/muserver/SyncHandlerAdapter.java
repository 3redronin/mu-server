package ronin.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class SyncHandlerAdapter implements AsyncMuHandler {

	private final List<MuHandler> muHandlers;
	private final ExecutorService executor = Executors.newCachedThreadPool();

	public SyncHandlerAdapter(List<MuHandler> muHandlers) {
		this.muHandlers = muHandlers;
	}


	public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
		if (headers.contains(HeaderNames.TRANSFER_ENCODING) || headers.getInt(HeaderNames.CONTENT_LENGTH, -1) > 0) {
			// There will be a request body, so set the streams
			GrowableByteBufferInputStream requestBodyStream = new GrowableByteBufferInputStream();
			((NettyRequestAdapter) ctx.request).inputStream(requestBodyStream);
			ctx.state = requestBodyStream;
		}
		executor.submit(() -> {
			try {

				boolean handled = false;
				for (MuHandler muHandler : muHandlers) {
					handled = muHandler.handle(ctx.request, ctx.response);
					if (handled) {
						break;
					}
				}
				if (!handled) {
				    MuServerHandler.send404(ctx);
				}

			} catch (Exception ex) {
				System.out.println("Error from handler: " + ex.getMessage());
				ex.printStackTrace();
			} finally {
				ctx.complete();
			}
		});
		return true;
	}

	public void onRequestData(AsyncContext ctx, ByteBuffer buffer) {
		GrowableByteBufferInputStream state = (GrowableByteBufferInputStream) ctx.state;
		state.handOff(buffer);
	}

	public void onRequestComplete(AsyncContext ctx) {
		try {
			GrowableByteBufferInputStream state = (GrowableByteBufferInputStream) ctx.state;
			if (state != null) {
				state.close();
			}
		} catch (IOException e) {
			System.out.println("This can't happen");
		}
	}

}
