package ronin.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class SyncHandlerAdapter implements AsyncMuHandler {

	private final List<MuHandler> muHandlers;
	private final ExecutorService executor = Executors.newCachedThreadPool();

	private static class State {
		final GrowableByteBufferInputStream reqBody = new GrowableByteBufferInputStream();
	}

	public SyncHandlerAdapter(List<MuHandler> muHandlers) {
		this.muHandlers = muHandlers;
	}


	public boolean onHeaders(AsyncContext ctx) throws Exception {
		State state = new State();
		ctx.state = state;
		((NettyRequestAdapter) ctx.request).inputStream(state.reqBody);
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
					ctx.response.status(404);
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

	public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
		State state = (State) ctx.state;
		GrowableByteBufferInputStream reqBody = state.reqBody;
		reqBody.handOff(buffer);
	}

	public void onRequestComplete(AsyncContext ctx) {
		State state = (State) ctx.state;
		try {
			state.reqBody.close();
		} catch (IOException e) {
			System.out.println("This can't happen");
		}
	}

}
