package validation;
import io.muserver.*;
import java.nio.ByteBuffer;
public final class EchoSocket extends BaseWebSocket {
    @Override public void onText(String text, boolean last, DoneCallback done) throws Exception {
        session().sendText(text, last, done);
    }
    @Override public void onBinary(ByteBuffer data, boolean last, DoneCallback done) throws Exception {
        session().sendBinary(data, last, done);
    }
}
