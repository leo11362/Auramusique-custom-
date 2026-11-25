package com.auramusic.app.utils

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a network call and retries it (2 s, then 5 s, then 10 s later) when it fails, so a weak
 * connection that drops one request does not leave the home page or the profile empty.
 */
suspend fun <T> retryNetwork(attempts: Int = 4, block: suspend () -> Result<T>): Result<T> {
    var last: Result<T> = Result.failure(IllegalStateException("no attempt"))
    val waits = longArrayOf(2_000, 5_000, 10_000)
    for (i in 0 until attempts) {
        last = try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
        if (last.isSuccess) return last
        timber.log.Timber.tag("Player").i("réseau : échec ${i + 1}/$attempts (${last.exceptionOrNull()?.javaClass?.simpleName})")
        if (i < attempts - 1) delay(waits[minOf(i, waits.size - 1)])
    }
    return last
}
