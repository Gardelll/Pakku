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
import io.ktor.util.date.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import teksturepako.pakku.api.PakkuApi
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.FileNotFound
import teksturepako.pakku.api.actions.errors.NoUrl
import teksturepako.pakku.api.actions.errors.ProjNotFound
import teksturepako.pakku.api.projects.ProjectFile
import teksturepako.pakku.debug
import teksturepako.pakku.toPrettyString
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
        // Reported as a connection error, the cancellation of the caller would be swallowed and retried.
        currentCoroutineContext().ensureActive()

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

/** How long the first retry waits at most; each further retry may wait twice as long as the previous one. */
private val INITIAL_RETRY_DELAY = 1.seconds

/** The longest a request waits before it is retried. */
internal val MAX_RETRY_DELAY = 1.minutes

/**
 * An exponentially growing delay before retry number [retryNumber], capped at [MAX_RETRY_DELAY].
 * It is randomised within its upper half, so that requests which failed together are not retried together.
 */
internal fun backoffDelay(retryNumber: Int, random: Random = Random.Default): Duration =
    (INITIAL_RETRY_DELAY * 2.0.pow(retryNumber - 1)).coerceAtMost(MAX_RETRY_DELAY) * random.nextDouble(0.5, 1.0)

/**
 * Parses the [value] of a `Retry-After` header, which is either a number of seconds or an HTTP date.
 * @return How long to wait from [nowMillis] on, or `null` if [value] is invalid.
 */
internal fun parseRetryAfter(value: String, nowMillis: Long = getTimeMillis()): Duration?
{
    value.trim().toLongOrNull()?.let { seconds -> return if (seconds >= 0) seconds.seconds else null }

    val date = runCatching { value.fromHttpToGmtDate() }.getOrNull() ?: return null
    return (date.timestamp - nowMillis).milliseconds.coerceAtLeast(Duration.ZERO)
}

/**
 * How long to wait before retry number [retryNumber] of a request which failed with this error,
 * or `null` if it should not be retried.
 */
internal fun ActionError.retryDelay(retryNumber: Int): Duration?
{
    if (!isTransient()) return null

    val backoff = backoffDelay(retryNumber)
    val requested = (this as? RequestError)?.response?.headers?.get(HttpHeaders.RetryAfter)
        ?.let { parseRetryAfter(it) }
        ?: return backoff

    // Retrying sooner than the server asks would fail again, and waiting longer would stall the command.
    return if (requested > MAX_RETRY_DELAY) null else maxOf(backoff, requested)
}

/**
 * Runs [request], repeating it while it fails for a reason which may resolve itself,
 * at most [maxRetries] times. [onRetry] is called before waiting for each new attempt.
 *
 * Each retry waits as long as the server asks for in a `Retry-After` header,
 * but at least an exponentially growing delay; see [retryDelay].
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

        if (retryNumber >= maxRetries) return result
        val wait = error.retryDelay(retryNumber + 1) ?: return result

        retryNumber++
        onRetry(retryNumber, error)
        delay(wait)
    }
}

/**
 * @return A body [ByteArray] of an HTTP(S) request, or an error if the status code is not OK.
 * A missing file is reported as [FileNotFound].
 *
 * A download which fails for a temporary reason is retried after a delay;
 * see [retryTransient] and [PakkuApi.Configuration.withMaxDownloadRetries].
 *
 * Project files should be downloaded from their [downloadUrl], which refuses URLs whose content cannot be trusted.
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
 * @return The URL to download this file from, or [NoUrl] if it has none.
 *
 * Without hashes, the downloaded content cannot be verified, so a non-HTTPS URL is refused as [InsecureUrl].
 * With hashes, HTTP is allowed, since the content is checked against them after the download.
 */
fun ProjectFile.downloadUrl(): Result<String, ActionError>
{
    val url = url ?: return Err(NoUrl(this))
    if (hashes.isNullOrEmpty() && !url.startsWith("https://", ignoreCase = true)) return Err(InsecureUrl(url))
    return Ok(url)
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
