import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.json.JSONObject;
import scaffolding.StringUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Path("/test")
public class TestResource {

    @GET
    @Path("{id}")
    @Produces("application/json")
    public String get(@PathParam("id") int id) {
        JSONObject p = new JSONObject()
            .put("id", id)
            .put("Name", StringUtils.randomStringOfLength(id * 10))
            .put("Surame", StringUtils.randomAsciiStringOfLength(id * 10));
        return p.toString(4);
    }


    public static void main(String[] args) throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(1000);
        HttpClient client = new HttpClient();
        client.start();

        for (int i = 1; i < 10000; i++) {

            int finalI = i;
            es.submit(() -> {
                try {
                    ContentResponse resp = client.newRequest("http://localhost:18080/test/" + finalI)
                        .header("num", String.valueOf(finalI))
                        .send();
                    String dec = resp.getHeaders().get("Content-Length");
                    if (dec != null) {
                        int deci = Integer.parseInt(dec);
                        int act = resp.getContent().length;
                        if (deci != act) {
                            System.out.println(finalI + ": dec = " + dec + "; act = " + act);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Error on " + finalI);
                    e.printStackTrace();
                }
            });

        }

        es.shutdown();
        boolean b = es.awaitTermination(1, TimeUnit.MINUTES);
        client.stop();

        System.out.println("Done? " + b);

    }
}
