package io.github.leugenea.codexbarmobile.transport

import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Caller owns exact selected routes; A7 owns protocol fields, A9 owns mapping. */
sealed class ProviderHttpRequest(val url: HttpUrl) {
    class Get(url: HttpUrl, internal val bearer: SensitiveValue) : ProviderHttpRequest(url)
    class FormPost(url: HttpUrl, fields: Map<String, SensitiveValue>) : ProviderHttpRequest(url) {
        internal val fields = fields.toMap()
    }
    class JsonPost(url: HttpUrl, internal val tree: JsonElement) : ProviderHttpRequest(url)

    override fun toString(): String = "ProviderHttpRequest(redacted)"
}

/** No client injection: production cannot install logging interceptors or unsafe TLS. */
class HttpTransportAdapter internal constructor(
    private val routes: (ReadOperation) -> HttpUrl,
    private val bearer: () -> SensitiveValue,
    appVersion: String,
    private val clock: TransportClock = SystemTransportClock,
    readTimeoutMillis: Long = ReadDeadline.MAX_DURATION_MILLIS,
    private val calls: (OkHttpClient, Request) -> Call = { client, request -> client.newCall(request) },
) : ProviderTransport {
    private val userAgent = "codexbar-mobile/$appVersion"
    internal val client: OkHttpClient

    init {
        require(appVersion.matches(Regex("[A-Za-z0-9.+_-]+"))) { "Invalid app version" }
        require(readTimeoutMillis in 1..ReadDeadline.MAX_DURATION_MILLIS) { "Invalid read timeout" }
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(ReadDeadline.MAX_DURATION_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun read(request: ReadRequest, terminal: (TransportResult) -> Unit): CancellationHandle =
        execute(ProviderHttpRequest.Get(routes(request.operation), bearer()), request.deadline, terminal)

    fun execute(
        request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit,
    ): CancellationHandle {
        val delivery = TerminalDelivery(deadline, clock, terminal)
        if (delivery.checkDeadline()) return delivery
        val call = try {
            calls(client, wireRequest(request))
        } catch (_: IllegalArgumentException) {
            delivery.complete(TransportResult.Failure(TransportFailure.INVALID_RESPONSE))
            return delivery
        } catch (_: java.nio.charset.CharacterCodingException) {
            delivery.complete(TransportResult.Failure(TransportFailure.INVALID_RESPONSE))
            return delivery
        }
        val remaining = remainingMillis(deadline)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        val owned = OwnedCall(call, delivery, deadline)
        owned.start(remaining)
        return owned
    }

    private fun wireRequest(request: ProviderHttpRequest): Request {
        val url = request.url
        // HTTP is only useful for JVM loopback fixtures, never a remote bearer route.
        require(url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1"))
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Accept", "application/json")
        when (request) {
            is ProviderHttpRequest.Get -> builder.header("Authorization", "Bearer ${text(request.bearer)}").get()
            is ProviderHttpRequest.FormPost -> builder.post(form(request))
            is ProviderHttpRequest.JsonPost -> builder.post(json(request.tree))
        }
        return builder.build()
    }

    private fun form(request: ProviderHttpRequest.FormPost): RequestBody {
        val builder = FormBody.Builder()
        request.fields.forEach { (key, value) -> builder.add(key, text(value)) }
        return builder.build()
    }

    private fun json(tree: JsonElement): RequestBody {
        val bytes = tree.toString().toByteArray(Charsets.UTF_8)
        require(JsonBoundary.parse(bytes) is JsonResult.Tree)
        return bytes.toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun text(value: SensitiveValue): String =
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value.copyBytes())).toString()

    private fun remainingMillis(deadline: ReadDeadline): Long =
        (deadline.expiresAtMillis - clock.now().monotonicMillis).coerceIn(1, ReadDeadline.MAX_DURATION_MILLIS)

    private inner class OwnedCall(
        private val call: Call, private val delivery: TerminalDelivery, private val deadline: ReadDeadline,
    ) : CancellationHandle, Callback {
        private var stopped = false
        private var callCancelled = false
        private var response: Response? = null
        private var publication: (() -> Unit)? = null
        private var timer: ScheduledFuture<*>? = null

        private fun schedule(remaining: Long): ScheduledFuture<*> = deadlines.schedule({
            deadlineWorkers.execute { expire() }
        }, remaining, TimeUnit.MILLISECONDS)

        fun start(remaining: Long) {
            synchronized(this) { timer = schedule(remaining) }
            call.enqueue(this)
        }

        private fun expire() {
            if (!deadline.isExpired(clock.now())) {
                synchronized(this) {
                    if (!stopped) timer = schedule(remainingMillis(deadline))
                }
                return
            }
            terminate { delivery.checkDeadline() }
        }

        override fun cancel() {
            terminate { delivery.cancel() }
        }

        /** One winner cancels the socket; the reader alone owns body closure. */
        private fun terminate(publish: () -> Unit) {
            synchronized(this) {
                if (stopped) return
                stopped = true
                timer?.cancel(false)
                publication = publish
            }
            call.cancel()
            synchronized(this) { callCancelled = true }
            publishAfterClosure()
        }

        private fun publishAfterClosure() {
            val publish = synchronized(this) {
                if (!callCancelled || response != null) null else publication.also { publication = null }
            }
            publish?.invoke()
        }

        override fun onFailure(call: Call, e: IOException) {
            finish(TransportResult.Failure(TransportFailure.NETWORK))
        }

        override fun onResponse(call: Call, response: Response) {
            val admitted = synchronized(this) {
                if (stopped) false else { this.response = response; true }
            }
            try {
                response.use {
                    if (admitted) finish(readBody(response))
                }
            } finally {
                if (admitted) {
                    synchronized(this) { this.response = null }
                    publishAfterClosure()
                }
            }
        }

        private fun readBody(response: Response): TransportResult = try {
            bounded(response)
        } catch (_: IOException) {
            TransportResult.Failure(TransportFailure.NETWORK)
        }

        private fun finish(result: TransportResult) {
            terminate { delivery.complete(result) }
        }
    }

    private fun bounded(response: Response): TransportResult {
        val body = response.body
        if (body.contentLength() > ResponseBody.MAX_BYTES) return oversized()
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        val source = body.byteStream()
        while (true) {
            val count = source.read(chunk, 0, minOf(chunk.size, ResponseBody.MAX_BYTES + 1 - output.size()))
            if (count == -1) break
            output.write(chunk, 0, count)
            if (output.size() > ResponseBody.MAX_BYTES) return oversized()
        }
        return TransportResult.Response.bounded(response.code, output.toByteArray(),
            RetryAfterParser.parse(response.header("Retry-After"), clock.now()))
    }

    private fun oversized(): TransportResult = TransportResult.Failure(TransportFailure.BODY_TOO_LARGE)

    private companion object {
        val deadlineWorkers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "provider-deadline-delivery").apply { isDaemon = true }
        }
        val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "provider-deadlines").apply { isDaemon = true }
        }
    }
}

object SystemTransportClock : TransportClock {
    private val origin = System.nanoTime()
    override fun now(): TransportTime = TransportTime(Instant.now(),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin).coerceAtLeast(0))
}
