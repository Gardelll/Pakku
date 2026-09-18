@file:Suppress("MemberVisibilityCanBePrivate")

package teksturepako.pakku.api.http

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.mapError
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import teksturepako.pakku.api.PakkuApi
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.FileNotFound
import teksturepako.pakku.api.actions.errors.ProjNotFound
import teksturepako.pakku.debug
import teksturepako.pakku.toPrettyString

class RequestError(val response: HttpResponse, val body: String? = null) : ActionError()
{
    override val rawMessage = message(
        "Error: (${response.call.request.url.host}) HTTP request ",
        "returned ${response.status}",
    )
}

class ConnectionError(val exception: Exception) : ActionError()
{
    override val rawMessage =
        "HTTP connection error: ${listOfNotNull(exception::class.simpleName, exception.message).joinToString(": ")}"
}

class InsecureUrl(val url: String) : ActionError()
{
    override val rawMessage =
        "Refusing non-HTTPS URL without verifiable hashes: '$url'."
}

suspend inline fun <reified T> tryRequest(block: () -> HttpResponse): Result<T, ActionError>
{
    return try
    {
        val response = block()

        debug { println(response.call) }

        when (response.status)
        {
            HttpStatusCode.OK       -> Ok(response.body<T>())
            HttpStatusCode.NotFound -> Err(ProjNotFound())
            else                    -> Err(RequestError(response, response.bodyAsText().toPrettyString()))
        }
    }
    catch (e: Exception)
    {
        debug { e.printStackTrace() }
        Err(ConnectionError(e))
    }
}

/** Bounds how many downloads buffer a whole file in memory at the same time. */
private val downloadSemaphore by lazy { Semaphore(PakkuApi.maxConcurrentDownloads) }

/** Whether the same request could still succeed if it were made again. */
private fun ActionError.isTransient(): Boolean = when (this)
{
    is ConnectionError -> true
    is RequestError    -> response.status.value >= 500 || response.status == HttpStatusCode.TooManyRequests
    else               -> false
}

/**
 * Runs [request], repeating it while it fails for a reason which may resolve itself,
 * at most [maxRetries] times. [onRetry] is called before each new attempt.
 */
internal suspend fun <T> retryTransient(
    maxRetries: Int,
    onRetry: suspend (retryNumber: Int, cause: ActionError) -> Unit = { _, _ -> },
    request: suspend () -> Result<T, ActionError>,
): Result<T, ActionError>
{
    var retryNumber = 0

    while (true)
    {
        val result = request()
        val error = result.getError() ?: return result

        if (!error.isTransient() || retryNumber >= maxRetries) return result

        retryNumber++
        onRetry(retryNumber, error)
    }
}

/**
 * @return A body [ByteArray] of an HTTP(S) request, or an error if the status code is not OK.
 * A missing file is reported as [FileNotFound].
 *
 * A download which fails for a temporary reason is retried;
 * see [PakkuApi.Configuration.withMaxDownloadRetries].
 *
 * Callers that cannot verify content hashes should reject non-HTTPS URLs before calling this
 * (see [requireHttpsWhenUnverifiable]).
 */
suspend fun requestByteArray(
    url: String,
    onRetry: suspend (retryNumber: Int, cause: ActionError) -> Unit = { _, _ -> },
    onDownload: suspend (bytesSentTotal: Long, contentLength: Long?) -> Unit = { _: Long, _: Long? -> }
): Result<ByteArray, ActionError> = retryTransient(PakkuApi.maxDownloadRetries, onRetry) {
    downloadSemaphore.withPermit {
        tryRequest<ByteArray> {
            pakkuClient.get(url) {
                onDownload { bytesSentTotal, contentLength -> onDownload(bytesSentTotal, contentLength) }
            }
        }
    }.mapError { error -> if (error is ProjNotFound) FileNotFound(url) else error }
}

/**
 * When [hashes] are missing, non-HTTPS URLs are refused because integrity cannot be checked.
 * When hashes are present, HTTP is allowed (content will be verified after download).
 */
fun requireHttpsWhenUnverifiable(url: String, hashes: Map<String, String>?): ActionError?
{
    if (!hashes.isNullOrEmpty()) return null
    if (url.startsWith("https://", ignoreCase = true)) return null
    return InsecureUrl(url)
}

/**
 * @return A body [String] of a https request, with headers provided or null if status code is not OK.
 */
suspend inline fun requestBody(
    url: String,
    vararg headers: Pair<String, String>?
): Result<String, ActionError> = tryRequest {
    pakkuClient.get(url) {
        headers.filterNotNull().forEach { this.headers.append(it.first, it.second) }
    }
}

/**
 * @return A body [String] of a https request, with headers provided or null if status code is not OK.
 */
suspend inline fun requestBody(
    url: String,
    bodyContent: () -> String,
    vararg headers: Pair<String, String>?
): Result<String, ActionError> = tryRequest {
    pakkuClient.post(url) {
        headers.filterNotNull().forEach { this.headers.append(it.first, it.second) }

        contentType(ContentType.Application.Json)
        setBody(bodyContent()) // Do not use pretty print
    }
}
