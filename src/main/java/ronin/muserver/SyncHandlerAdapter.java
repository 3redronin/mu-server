package ronin.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncHandlerAdapter implements MuHandler {

    private final SyncHandler syncHandler;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static MuHandler syncHandler(SyncHandler syncHandler) {
        return new SyncHandlerAdapter(syncHandler);
    }

    public SyncHandlerAdapter(SyncHandler syncHandler) {
        this.syncHandler = syncHandler;
    }


    public boolean onHeaders(AsyncContext ctx) throws Exception {
        return true;
    }

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {

    }

    public void onRequestComplete(AsyncContext ctx) {
        executor.submit(() -> {
            try {
                syncHandler.handle(ctx.request, ctx.response);
            } catch (Exception ex) {
                System.out.println("Error from handler: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                ctx.complete();
            }
        });
    }
}
