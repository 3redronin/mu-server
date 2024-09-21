package io.muserver

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import scaffolding.ClientUtils.client
import scaffolding.ServerUtils
import scaffolding.call
import scaffolding.toRequest


class ConnectionTest {

    @ParameterizedTest
    @ValueSource(strings = ["http", "https"])
    fun `if the request asks to close the connection then it is closed`(protocol: String) {
        val stats = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/") { request, response, _ ->
                response.write(request.query().get("message"))
            }
            .start().use { server ->
                for (i in 0..1) {
                    client.call(
                        server.uri().resolve("?message=my-first-message").toRequest()
                            .header("connection", "close")
                    ).use { resp ->
                        assertThat(resp.body?.string(), equalTo("my-first-message"))
                    }
                }
                server.stats()
            }
        assertThat(stats.completedConnections(),equalTo(2L))

    }


    @ParameterizedTest
    @ValueSource(strings = ["http", "https"])
    fun `if the response asks to close the connection then it is closed`(protocol: String) {
        val stats = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/") { request, response, _ ->
                response.headers().set("connection", "close")
                response.write(request.query().get("message"))
            }
            .start().use { server ->
                for (i in 0..1) {
                    client.call(
                        server.uri().resolve("?message=my-first-message").toRequest()
                    ).use { resp ->
                        assertThat(resp.body?.string(), equalTo("my-first-message"))
                    }
                }
                server.stats()
            }
        assertThat(stats.completedConnections(),equalTo(2L))
    }

    @ParameterizedTest
    @ValueSource(strings = ["http", "https"])
    @Disabled("The connections are not shut down")
    fun `if the nothing asks to close the connection then it is reused`(protocol: String) {
        val stats = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/") { request, response, _ ->
                response.write(request.query().get("message"))
            }
            .start().use { server ->
                for (i in 0..1) {
                    client.call(
                        server.uri().resolve("?message=my-first-message").toRequest()
                    ).use { resp ->
                        assertThat(resp.body?.string(), equalTo("my-first-message"))
                    }
                }
                server.stats()
            }
        assertThat(stats.completedConnections(),equalTo(1))
    }


}

