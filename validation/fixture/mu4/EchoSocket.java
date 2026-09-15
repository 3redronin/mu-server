package validation;
import io.muserver.*;
import java.nio.ByteBuffer;
public final class EchoSocket extends SimpleWebSocket {
    @Override public void onText(String text) throws Exception { session().sendText(text); }
    @Override public void onBinary(ByteBuffer data) throws Exception { session().sendBinary(data); }
}
