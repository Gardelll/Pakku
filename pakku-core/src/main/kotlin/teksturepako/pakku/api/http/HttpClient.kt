package teksturepako.pakku.api.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import teksturepako.pakku.api.PakkuApi
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

val pakkuClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        Json
    }
    install(HttpTimeout) {
        // A download is bound by how long it can stall, not by how long it takes in total,
        // so that large files are not cancelled while they are still making progress.
        socketTimeoutMillis = PakkuApi.timeout.inWholeMilliseconds
        connectTimeoutMillis = PakkuApi.connectTimeout.inWholeMilliseconds
        requestTimeoutMillis = PakkuApi.requestTimeout?.inWholeMilliseconds
            ?: HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
    install(UserAgent) {
        PakkuApi.userAgent?.let { agent = it }
    }
    engine {
        pipelining = true
        config {
            retryOnConnectionFailure(true)

            connectTimeout(PakkuApi.connectTimeout.toJavaDuration())
            readTimeout(PakkuApi.timeout.toJavaDuration())
            writeTimeout(PakkuApi.timeout.toJavaDuration())

            // `ZERO` disables the limit on the duration of the whole call.
            callTimeout(PakkuApi.requestTimeout?.toJavaDuration() ?: JavaDuration.ZERO)
        }
    }
}
