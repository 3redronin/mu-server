package ronin.muserver;


import java.nio.ByteBuffer;

public interface AsyncMuHandler {

	boolean onHeaders(AsyncContext ctx) throws Exception;

	void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception;

	void onRequestComplete(AsyncContext ctx);


}
