package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        check(Character.MAX_VALUE);
        check('好');
        check('�');
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        check(Byte.MAX_VALUE);
        check(Byte.MIN_VALUE);
        check((byte)0);
        check((byte)1);
        checkNoBody();
    }

    @Test
    public void floatsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public float echo(float value) {
                return value;
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        check(Float.MAX_VALUE);
        check(Float.MIN_VALUE);
        check((float)0);
        check((float)0.0);
        check((float)3.14);
        checkNoBody();
    }

    @Test
    public void doublesSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public double echo(double value) {
                return value;
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        check(Double.MAX_VALUE);
        check(Double.MIN_VALUE);
        check((double)0);
        check((double)0.0);
        check((double)3.14);
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
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request()
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("text/plain;charset=UTF-8"));
            assertThat(resp.body().string(), equalTo("123"));
        }
    }


    private void check(Object value) throws IOException {
        String content = String.valueOf(value);
        try (Response resp = call(request()
            .post(RequestBody.create(MediaType.parse("text/plain;charset=UTF-8"), content))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("text/plain;charset=UTF-8"));
//            assertThat(resp.header("Content-Length"), equalTo("" + value.toString().getBytes(UTF_8).length));
            assertThat(resp.body().string(), equalTo(content));
        }
    }
    private void checkNoBody() throws IOException {
        try (Response resp = call(request()
            .post(RequestBody.create(MediaType.parse("text/plain"), ""))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("400 Bad Request"));
        }
    }


    @After
    public void stop() {
        stopAndCheck(server);
    }

}