package snd.komelia.ui.reader.image.panels

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.reactivecircus.cache4k.Cache
import io.github.reactivecircus.cache4k.CacheEvent.Evicted
import io.github.reactivecircus.cache4k.CacheEvent.Expired
import io.github.reactivecircus.cache4k.CacheEvent.Removed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

private val pageLoadCoordinatorLogger = KotlinLogging.logger {}

@OptIn(ExperimentalAtomicApi::class)
internal class PageLoadCoordinator<K : Any, V : Any>(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanupScope: CoroutineScope,
    maximumCacheSize: Long = 10,
    private val closeValue: (V) -> Unit,
) {
    private val loadScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val navigationScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var navigationJob: Job? = null
    private val publicationMutex = Mutex()
    private val latestUnpublish = AtomicReference<Job?>(null)
    private val currentEntry = AtomicReference<CacheEntry?>(null)

    private val cache = Cache.Builder<K, CacheEntry>()
        .maximumCacheSize(maximumCacheSize)
        .eventListener { event ->
            val entry = when (event) {
                is Evicted -> event.value
                is Expired -> event.value
                is Removed -> event.value
                else -> null
            } ?: return@eventListener
            entry.release()
        }
        .build()

    fun prefetch(key: K, load: suspend () -> V): Deferred<V> {
        val entry = acquire(key, load)
        entry.release()
        return entry.deferred
    }

    fun <R> navigate(
        key: K,
        load: suspend () -> V,
        prepare: suspend (V) -> R,
        publish: (R) -> Boolean,
        afterReady: suspend () -> Unit,
    ): Job {
        navigationJob?.cancel()
        val entry = acquire(key, load)
        return navigationScope.launch {
            var currentOwnership = false
            try {
                val value = entry.deferred.await()
                val readyValue = prepare(value)
                latestUnpublish.load()?.join()
                val published = publicationMutex.withLock {
                    check(entry.tryRetain()) { "Prepared page was disposed before publication" }
                    currentOwnership = true
                    currentCoroutineContext().ensureActive()
                    if (!publish(readyValue)) return@withLock false

                    val previousEntry = currentEntry.exchange(entry)
                    currentOwnership = false
                    previousEntry?.release()
                    true
                }
                if (published) afterReady()
            } finally {
                if (currentOwnership) entry.release()
                entry.release()
            }
        }.also { navigationJob = it }
    }

    fun unpublishCurrent(unpublish: () -> Unit): Job {
        val completion = Job()
        val previousUnpublish = latestUnpublish.exchange(completion)
        val cleanupJob = cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            previousUnpublish?.join()
            publicationMutex.withLock {
                unpublish()
                currentEntry.exchange(null)?.release()
            }
        }
        cleanupJob.invokeOnCompletion { completion.complete() }
        return cleanupJob
    }

    fun stop(unpublishCurrent: () -> Unit): Job {
        navigationJob?.cancel()
        navigationScope.coroutineContext.cancelChildren()
        val cleanupJob = unpublishCurrent(unpublishCurrent)
        cache.invalidateAll()
        loadScope.coroutineContext.cancelChildren()
        return cleanupJob
    }

    private fun acquire(key: K, load: suspend () -> V): CacheEntry {
        while (true) {
            val cached = cache.get(key)
            if (cached != null && !cached.deferred.isCancelled && cached.tryRetain()) {
                return cached
            }

            val entry = CacheEntry(loadScope.async { load() })
            check(entry.tryRetain())
            cache.put(key, entry)
            return entry
        }
    }

    private inner class CacheEntry(val deferred: Deferred<V>) {
        private val owners = AtomicInt(1)

        fun tryRetain(): Boolean {
            while (true) {
                val currentOwners = owners.load()
                if (currentOwners == 0) return false
                if (owners.compareAndSet(currentOwners, currentOwners + 1)) return true
            }
        }

        fun release() {
            val remainingOwners = owners.decrementAndFetch()
            check(remainingOwners >= 0) { "Page load ownership released more than once" }
            if (remainingOwners != 0) return

            cleanupScope.launch {
                if (deferred.isActive) deferred.cancel()
                try {
                    closeValue(deferred.await())
                } catch (_: CancellationException) {
                    // A canceled load never produced a value to close.
                } catch (e: Throwable) {
                    pageLoadCoordinatorLogger.warn(e) { "Failed to dispose cached page load" }
                }
            }
        }
    }
}
