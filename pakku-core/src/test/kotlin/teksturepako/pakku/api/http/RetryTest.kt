package teksturepako.pakku.api.http

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.FileNotFound
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetryTest
{
    private val transient = ConnectionError(IOException("connection reset"))

    private fun serverError(status: HttpStatusCode, retryAfter: String? = null) = RequestError(
        mockk<HttpResponse>(relaxed = true) {
            every { this@mockk.status } returns status
            every { headers } returns if (retryAfter != null) headersOf(HttpHeaders.RetryAfter, retryAfter) else headersOf()
        }
    )

    @Test
    fun `a transient failure is retried up to the limit`() = runTest {
        var attempts = 0

        val result = retryTransient<String>(maxRetries = 2) {
            attempts++
            Err(transient)
        }

        assertEquals(3, attempts)
        assertEquals(transient, result.getError())
    }

    @Test
    fun `retrying stops as soon as it succeeds`() = runTest {
        var attempts = 0

        val result = retryTransient(maxRetries = 5) {
            attempts++
            if (attempts < 3) Err(transient) else Ok("done")
        }

        assertEquals(3, attempts)
        assertEquals("done", result.get())
    }

    @Test
    fun `a permanent failure is not retried`() = runTest {
        var attempts = 0

        val result = retryTransient<String>(maxRetries = 3) {
            attempts++
            Err(FileNotFound("https://example.invalid/gone.jar"))
        }

        assertEquals(1, attempts)
        assertIs<FileNotFound>(result.getError())
    }

    @Test
    fun `retrying can be disabled`() = runTest {
        var attempts = 0

        retryTransient<String>(maxRetries = 0) {
            attempts++
            Err(transient)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `each retry is reported`() = runTest {
        val reported = mutableListOf<Int>()

        retryTransient<String>(maxRetries = 2, onRetry = { retryNumber, _ -> reported += retryNumber }) {
            Err(transient)
        }

        assertEquals(listOf(1, 2), reported)
    }

    @Test
    fun `retries wait exponentially longer`() = runTest {
        val attemptTimes = mutableListOf<Long>()

        retryTransient<String>(maxRetries = 3) {
            attemptTimes += currentTime
            Err(transient)
        }

        val waits = attemptTimes.zipWithNext { previous, next -> next - previous }

        assertEquals(3, waits.size)
        waits.forEachIndexed { index, wait ->
            val limit = 1000L shl index
            assertTrue(wait in limit / 2..limit, "retry ${index + 1} waited $wait ms instead of up to $limit ms")
        }
    }

    @Test
    fun `a retry waits as long as the server asks`() = runTest {
        val attemptTimes = mutableListOf<Long>()

        retryTransient<String>(maxRetries = 1) {
            attemptTimes += currentTime
            Err(serverError(HttpStatusCode.TooManyRequests, retryAfter = "30"))
        }

        assertEquals(30_000L, attemptTimes[1] - attemptTimes[0])
    }

    @Test
    fun `a server asking to wait too long is not retried`() = runTest {
        var attempts = 0

        retryTransient<String>(maxRetries = 2) {
            attempts++
            Err(serverError(HttpStatusCode.ServiceUnavailable, retryAfter = "3600"))
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `a cancelled request is neither retried nor reported as failed`() = runTest {
        var attempts = 0
        val reported = mutableListOf<Int>()
        var result: Result<String, ActionError>? = null

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result = retryTransient(maxRetries = 2, onRetry = { retryNumber, _ -> reported += retryNumber }) {
                tryRequest<String> {
                    attempts++
                    awaitCancellation()
                }
            }
        }
        job.cancelAndJoin()

        assertEquals(1, attempts)
        assertEquals(emptyList(), reported)
        assertNull(result)
    }

    @Test
    fun `backoff grows exponentially up to its cap`()
    {
        val random = Random(0)

        for (retryNumber in 1..10)
        {
            val limit = minOf(1.seconds * (1 shl (retryNumber - 1)), MAX_RETRY_DELAY)
            val delay = backoffDelay(retryNumber, random)

            assertTrue(delay >= limit / 2 && delay <= limit, "retry $retryNumber waits $delay instead of up to $limit")
        }

        assertTrue(backoffDelay(Int.MAX_VALUE, random) <= MAX_RETRY_DELAY)
    }

    @Test
    fun `Retry-After is parsed as seconds or as an HTTP date`()
    {
        val now = 784_111_777_000L // Sun, 06 Nov 1994 08:49:37 GMT

        assertEquals(120.seconds, parseRetryAfter("120", now))
        assertEquals(0.seconds, parseRetryAfter("0", now))
        assertEquals(2.minutes, parseRetryAfter("Sun, 06 Nov 1994 08:51:37 GMT", now))
        assertEquals(0.milliseconds, parseRetryAfter("Sun, 06 Nov 1994 08:48:37 GMT", now))
        assertNull(parseRetryAfter("-5", now))
        assertNull(parseRetryAfter("1.5", now))
        assertNull(parseRetryAfter("soon", now))
    }

    @Test
    fun `only transient failures have a retry delay`()
    {
        assertNull(FileNotFound("https://example.invalid/gone.jar").retryDelay(1))
        assertNull(serverError(HttpStatusCode.Forbidden).retryDelay(1))
        assertTrue(serverError(HttpStatusCode.BadGateway).retryDelay(1)!! <= 1.seconds)
        assertEquals(45.seconds, serverError(HttpStatusCode.ServiceUnavailable, retryAfter = "45").retryDelay(1))
    }
}
