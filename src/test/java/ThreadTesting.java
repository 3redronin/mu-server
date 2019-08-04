import io.muserver.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.muServer;

public class ThreadTesting {

    public static void main(String[] args) throws Exception {

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(20);

        MuServer server = muServer()
            .withHttpsPort(12000)
            .addHandler(WebSocketHandlerBuilder.webSocketHandler()
                .withPath("/ws")
                .withWebSocketFactory(new MuWebSocketFactory() {
                    @Override
                    public MuWebSocket create(MuRequest request, Headers responseHeaders) throws Exception {
                        return new BaseWebSocket() {

                            @Override
                            public void onConnect(MuWebSocketSession session) throws Exception {
                                super.onConnect(session);
                                send();
                            }

                            private void send() {
                                session().sendText("A message", error -> {
                                    if (error == null) {
                                        executorService.schedule(this::send, 5, TimeUnit.SECONDS);
                                    }
                                });
                            }


                            @Override
                            public void onText(String message, DoneCallback onComplete) throws Exception {
                                super.onText(message, onComplete);
                            }
                        };
                    }
                })
            )
            .addHandler(Method.GET, "/async", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                executorService.schedule(() -> {
                    response.status(400);
                    handle.write(Mutils.toByteBuffer("Done done odne d!!!!!!!!!!!!!!!!!!!!!"), throwable -> handle.complete());
                }, 20, TimeUnit.SECONDS);
            })
            .addHandler((request, response) -> {
                SsePublisher publisher = SsePublisher.start(request, response);
                for (int i = 0; i < (Math.random() * 100); i++) {
                    publisher.send("Hello " + i);
                    Thread.sleep(500);
                }
                publisher.close();
                return true;
            })
            .start();


        while (true) {
            long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long mb = mem / 1000000;
            System.out.println(mb + "mb used for " + server.stats());
            Thread.sleep(1000);
        }
    }
}
