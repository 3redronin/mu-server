package ronin.muserver.rest;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import ronin.muserver.MuServer;
import scaffolding.ClientUtils;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static scaffolding.ClientUtils.call;

public class PrimitiveEntityProviderTest {

    private MuServer server;

    @Test
    public void booleansSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public boolean echo(boolean value) {
                return value;
            }
        }
        startServer(new Sample());
        check(true);
        check(false);
        checkNoBody();
    }

    @Test
    public void integersSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public int echo(int value) {
                return value;
            }
        }
        startServer(new Sample());
        check(Integer.MAX_VALUE);
        check(Integer.MIN_VALUE);
        check(0);
        check(128);
        check(-128);
        checkNoBody();
    }

    @Test
    public void shortsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public short echo(short value) {
                return value;
            }
        }
        startServer(new Sample());
        check(Short.MAX_VALUE);
        check(Short.MIN_VALUE);
        check((short)0);
        check((short)128);
        check((short)-128);
        checkNoBody();
    }

    @Test
    public void longsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public long echo(long value) {
                return value;
            }
        }
        startServer(new Sample());
        check(Long.MAX_VALUE);
        check(Long.MIN_VALUE);
        check((long)0);
        check((long)128);
        check((long)-128);
        checkNoBody();
    }

    @Test
    public void charsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public char echo(char value) {
                return value;
            }
        }
        startServer(new Sample());
        check(Character.MAX_VALUE);
        check(Character.MIN_VALUE);
        check('h');
        check('i');
        checkNoBody();
    }

    @Test
    public void bytesSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public byte echo(byte value) {
                return value;
            }
        }
        startServer(new Sample());
        check(Byte.MAX_VALUE);
        check(Byte.MIN_VALUE);
        check((byte)0);
        check((byte)1);
        checkNoBody();
    }

    @Test
    public void numbersCanBeReturned() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public Number echo() {
                return 123;
            }
        }
        startServer(new Sample());
        Response resp = call(ClientUtils.request()
            .url(server.uri().resolve("/samples").toString())
        );
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("Content-Type"), equalTo("text/plain"));
        assertThat(resp.body().string(), equalTo("123"));
    }


    private void check(Object value) throws IOException {
        String content = String.valueOf(value);
        Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse("text/plain"), content))
            .url(server.uri().resolve("/samples").toString())
        );
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("Content-Type"), equalTo("text/plain"));
        assertThat(resp.body().string(), equalTo(content));
    }
    private void checkNoBody() throws IOException {
        Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse("text/plain"), ""))
            .url(server.uri().resolve("/samples").toString())
        );
        assertThat(resp.code(), equalTo(400));
        assertThat(resp.body().string(), equalTo("400 Bad Request"));

    }


    private void startServer(Object restResource) {
        this.server = httpsServer().addHandler(new RestHandler(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}