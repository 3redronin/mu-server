package io.muserver;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;

public class Mu3ServerImplTest {

    @Test
    public void responseWriteWorks() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                String msg = request.query().get("message");
                response.write(msg);
            })
            .start()) {

            try (var resp = call(client, request(server.uri().resolve("?message=my-first-message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.headers().get("content-type"), equalTo("text/plain;charset=utf-8"));
                assertThat(resp.body().string(), equalTo("my-first-message"));
            }

            try (var resp = call(client, request(server.uri().resolve("?message=my%20second%20message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("my second message"));
            }
        }
    }

    @Test
    public void readingFromBodyWorks() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                String msg = request.readBodyAsString();
                response.write(msg);
            })
            .start()) {

            try (var resp = call(client, request(server.uri())
                .post(RequestBody.create("my-first-message", MediaType.parse("text/plain"))))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.headers().get("content-type"), equalTo("text/plain;charset=utf-8"));
                assertThat(resp.body().string(), equalTo("my-first-message"));
            }

            try (var resp = call(client, request(server.uri())
                .post(RequestBody.create("my second message", MediaType.parse("text/plain"))))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("my second message"));
            }
        }
    }

    @Test
    public void sendChunkWorks() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                String msg = request.query().get("message");
                response.sendChunk("hey: ");
                response.sendChunk(msg);
            })
            .start()) {

            try (var resp = call(client, request(server.uri().resolve("?message=my-first-message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.headers().get("content-type"), equalTo("text/plain;charset=utf-8"));
                assertThat(resp.body().string(), equalTo("hey: my-first-message"));
            }

            try (var resp = call(client, request(server.uri().resolve("?message=my%20second%20message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("hey: my second message"));
            }
        }
    }

    @Test
    public void responseOutputStreamWorks() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                String msg = request.query().get("message");
                response.contentType("text/plain; charset=utf-8");
                try (var out = response.outputStream()) {
                    for (char c : msg.toCharArray()) {
                        out.write(String.valueOf(c).getBytes());
                        if (c == 'm') {
                            out.flush();
                        }
                    }
                }
            })
            .start()) {

            try (var resp = call(client, request(server.uri().resolve("?message=my-first-message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("my-first-message"));
            }

            try (var resp = call(client, request(server.uri().resolve("?message=my%20second%20message")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("my second message"));
            }
        }
    }

    @Test
    public void httpsWorks() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hello, world");
            })
            .start()) {

            try (var resp = call(client, request(server.uri()))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello, world"));
            }

            try (var resp = call(client, request(server.uri()))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello, world"));
            }
        }
    }

    @Test
    public void httpsWorks2() throws IOException {
        try (MuServer server = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hello, world");
            })
            .start()) {

            try (var resp = call(client, request(server.uri()))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello, world"));
            }
        }
    }
}