import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.Mutils;
import io.muserver.handlers.ResourceHandlerBuilder;

import java.io.File;

import static io.muserver.Http2ConfigBuilder.http2Enabled;
import static io.muserver.MuServerBuilder.muServer;

public class ManyFiles {

    public static void main(String[] args) {
        File dir = new File(System.getenv("DIR_WITH_LOTS_OF_JPGS"));
        MuServer server = muServer()
            .withHttpsPort(14000)
            .withHttp2Config(http2Enabled().enabled(false))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_HTML_UTF8);
                response.sendChunk("<h1>Photos</h1>");
                for (File file : dir.listFiles()) {
                    if (file.getName().endsWith(".jpg")) {
                        response.sendChunk("<img src=\"/" + Mutils.htmlEncode(Mutils.urlEncode(file.getName())) + "\" style=\"width: 100px; height: 100px;\">");
                    }
                }
                response.sendChunk("<hr>");
            })
            .addHandler(ResourceHandlerBuilder.fileHandler(dir))
            .addShutdownHook(true)
            .start();

        System.out.println("Started " + server.uri());
    }

}
