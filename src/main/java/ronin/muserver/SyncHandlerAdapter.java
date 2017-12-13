package ronin.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncHandlerAdapter implements MuAsyncHandler {

    private final AsyncContext ctx;
    private final SyncHandler syncHandler;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static MuHandler syncHandler(SyncHandler syncHandler) {
        return new MuHandler() {
            @Override
            public MuAsyncHandler start(AsyncContext ctx) {
                return new SyncHandlerAdapter(ctx, syncHandler);
            }
        };
    }

    public SyncHandlerAdapter(AsyncContext ctx, SyncHandler syncHandler) {
        this.ctx = ctx;
        this.syncHandler = syncHandler;
    }

    @Override
    public void onHeaders() throws Exception {
    }

    @Override
    public void onRequestData(ByteBuffer buffer) throws Exception {

    }

    @Override
    public void onRequestComplete() {
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
