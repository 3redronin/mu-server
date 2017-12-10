package ronin.muserver;

import java.nio.ByteBuffer;

public interface MuAsyncHandler {

	void onHeaders() throws Exception;

	void onRequestData(ByteBuffer buffer) throws Exception;

	void onRequestComplete();

}
