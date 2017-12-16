package ronin.muserver;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncHandlerAdapter implements AsyncMuHandler {

    private final List<MuHandler> muHandlers;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public SyncHandlerAdapter(List<MuHandler> muHandlers) {
        this.muHandlers = muHandlers;
    }


    public boolean onHeaders(AsyncContext ctx) throws Exception {
        return true;
    }

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {

    }

    public void onRequestComplete(AsyncContext ctx) {
        executor.submit(() -> {
            try {
                for (MuHandler muHandler : muHandlers) {
                    boolean handled = muHandler.handle(ctx.request, ctx.response);
                    if (handled) {
                        break;
                    }
                }
            } catch (Exception ex) {
                System.out.println("Error from handler: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                ctx.complete();
            }
        });
    }
}
