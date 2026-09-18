package teksturepako.pakku.api.http

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import teksturepako.pakku.api.PakkuApi
import kotlin.time.Duration
import kotlin.time.toJavaDuration

val pakkuClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        Json
    }
    install(HttpTimeout) {
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
            val stallTimeout = PakkuApi.timeout.toJavaDuration()

            retryOnConnectionFailure(true)

            connectTimeout(PakkuApi.connectTimeout.toJavaDuration())
            readTimeout(stallTimeout)
            writeTimeout(stallTimeout)

            // `ZERO` disables the limit on the duration of the whole call.
            callTimeout((PakkuApi.requestTimeout ?: Duration.ZERO).toJavaDuration())
        }
    }
}
