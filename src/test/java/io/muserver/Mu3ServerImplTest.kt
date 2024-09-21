package io.muserver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import scaffolding.ClientUtils.client
import scaffolding.call
import scaffolding.toRequest

class Mu3ServerImplTest {

    @Test
    fun `response write works`() {
        MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(
                Method.GET, "/"
            ) { request, response, _ ->
                val msg = request.query().get("message")
                response.write(msg)
            }
            .start().use { server ->
                client.call(server.uri().resolve("?message=my-first-message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.headers["content-type"], equalTo("text/plain;charset=utf-8"))
                    assertThat(resp.body?.string(), equalTo("my-first-message"))
                }
                client.call(server.uri().resolve("?message=my%20second%20message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("my second message"))
                }

            }
    }

    @Test
    fun `reading from body works`() {
        MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(
                Method.POST, "/"
            ) { request, response, _ ->
                val msg = request.readBodyAsString()
                response.write(msg)
            }
            .start().use { server ->
                client.call(server.uri().toRequest()
                    .post("my-first-message".toRequestBody("text/plain".toMediaType()))
                ).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.headers["content-type"], equalTo("text/plain;charset=utf-8"))
                    assertThat(resp.body?.string(), equalTo("my-first-message"))
                }
                client.call(server.uri().toRequest()
                    .post("my second message".toRequestBody("text/plain".toMediaType()))
                ).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("my second message"))
                }

            }

    }

    @Test
    fun `send chunk works`() {
        MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(
                Method.GET, "/"
            ) { request, response, _ ->
                val msg = request.query().get("message")
                response.sendChunk("hey: ")
                response.sendChunk(msg)
            }
            .start().use { server ->
                client.call(server.uri().resolve("?message=my-first-message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.headers["content-type"], equalTo("text/plain;charset=utf-8"))
                    assertThat(resp.body?.string(), equalTo("hey: my-first-message"))
                }
                client.call(server.uri().resolve("?message=my%20second%20message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("hey: my second message"))
                }

            }
    }


    @Test
    fun `response outputStream works`() {
        MuServerBuilder.muServer()
            .withHttpPort(0)
            .addHandler(
                Method.GET, "/"
            ) { request, response, _ ->
                val msg = request.query().get("message")
                response.contentType("text/plain; charset=utf-8")
                response.outputStream().use { out ->

                    for (c in msg) {
                        out.write(c.toString().toByteArray())
                        if (c == 'm') out.flush()
                    }

                }
            }
            .start().use { server ->
                client.call(server.uri().resolve("?message=my-first-message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("my-first-message"))
                }
                client.call(server.uri().resolve("?message=my%20second%20message").toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("my second message"))
                }

            }
    }



    @Test
    fun `https works`() {
        MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(
                Method.GET, "/"
            ) { _, response, _ ->
                response.write("Hello, world")
            }
            .start().use { server ->
                client.call(server.uri().toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("Hello, world"))
                }
                client.call(server.uri().toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("Hello, world"))
                }
            }
    }

    @Test
    fun `https works 2`() {
        MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(
                Method.GET, "/"
            ) { _, response, _ ->
                response.write("Hello, world")
            }
            .start().use { server ->
                client.call(server.uri().toRequest()).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("Hello, world"))
                }
            }
    }



}

