package io.muserver;


import java.nio.ByteBuffer;

/**
 * @deprecated This interface is no longer used. Instead call {@link MuRequest#handleAsync()} from a standard Mu Handler.
 */
@Deprecated
public interface AsyncMuHandler {

	boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception;

	void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception;

	void onRequestComplete(AsyncContext ctx);


}
