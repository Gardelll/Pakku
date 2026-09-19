package teksturepako.pakku.io

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val FILE_IO_CONCURRENCY = 8

suspend inline fun <T, R> Iterable<T>.mapAsync(
    concurrency: Int = Int.MAX_VALUE,
    crossinline transform: suspend (T) -> R
): List<R> = coroutineScope {
    require(concurrency > 0) { "concurrency must be positive" }

    val semaphore = Semaphore(concurrency)
    this@mapAsync.map { item ->
        async {
            semaphore.withPermit {
                transform(item)
            }
        }
    }.awaitAll()
}

suspend inline fun <T, R : Any> Iterable<T>.mapAsyncNotNull(
    concurrency: Int = Int.MAX_VALUE,
    crossinline transform: suspend (T) -> R?
): List<R> = this.mapAsync(concurrency, transform).filterNotNull()
