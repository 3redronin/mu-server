package scaffolding

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

fun URI.toRequest() = Request.Builder().url(this.toString())

fun OkHttpClient.call(request: Request.Builder) = this.newCall(request.build()).execute()
fun OkHttpClient.call(uri: URI) = this.call(uri.toRequest())
