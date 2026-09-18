package teksturepako.pakku.api.http

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import teksturepako.pakku.api.actions.errors.FileNotFound
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetryTest
{
    private val transient = ConnectionError(IOException("connection reset"))

    @Test
    fun `a transient failure is retried up to the limit`(): Unit = runBlocking {
        var attempts = 0

        val result = retryTransient<String>(maxRetries = 2) {
            attempts++
            Err(transient)
        }

        assertEquals(3, attempts)
        assertEquals(transient, result.getError())
    }

    @Test
    fun `retrying stops as soon as it succeeds`(): Unit = runBlocking {
        var attempts = 0

        val result = retryTransient(maxRetries = 5) {
            attempts++
            if (attempts < 3) Err(transient) else Ok("done")
        }

        assertEquals(3, attempts)
        assertEquals("done", result.get())
    }

    @Test
    fun `a permanent failure is not retried`(): Unit = runBlocking {
        var attempts = 0

        val result = retryTransient<String>(maxRetries = 3) {
            attempts++
            Err(FileNotFound("https://example.invalid/gone.jar"))
        }

        assertEquals(1, attempts)
        assertIs<FileNotFound>(result.getError())
    }

    @Test
    fun `retrying can be disabled`(): Unit = runBlocking {
        var attempts = 0

        retryTransient<String>(maxRetries = 0) {
            attempts++
            Err(transient)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `each retry is reported`(): Unit = runBlocking {
        val reported = mutableListOf<Int>()

        retryTransient<String>(maxRetries = 2, onRetry = { retryNumber, _ -> reported += retryNumber }) {
            Err(transient)
        }

        assertEquals(listOf(1, 2), reported)
    }
}
