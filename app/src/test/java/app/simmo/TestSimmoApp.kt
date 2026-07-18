package app.simmo

import androidx.datastore.core.DataStore
import app.simmo.store.SimmoState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [android.app.Application] every Robolectric test runs against (wired up
 * globally in `robolectric.properties`). It is a real [SimmoApp] in every
 * respect except that its persisted-state store is a fresh in-memory store per
 * test instead of the process-wide on-disk [app.simmo.store.simmoStateStore]
 * singleton.
 *
 * The singleton is the root of the "tests hung for several minutes" reports:
 * `by dataStore(...)` caches one instance for the whole JVM keyed to the first
 * context that touches it, so a later test reuses a store bound to an earlier
 * test's torn-down sandbox. Every read then fails, and [SimmoStateHolder]'s
 * deliberately unbounded read retry (correct in production, where a null state
 * just means "proceed unmodified") leaves the state forever null — so any
 * `state.first()` await hangs until the CI job's own timeout kills it. A fresh
 * store per test can never be reused or torn down mid-run, so the read never
 * fails and nothing hangs. It is also faster: no disk I/O.
 */
class TestSimmoApp : SimmoApp() {
    private val store = InMemoryStateStore()

    override fun createStateStore(): DataStore<SimmoState> = store

    private class InMemoryStateStore : DataStore<SimmoState> {
        private val flow = MutableStateFlow(SimmoState())
        private val writeLock = Mutex()

        override val data: Flow<SimmoState> = flow

        override suspend fun updateData(
            transform: suspend (t: SimmoState) -> SimmoState,
        ): SimmoState = writeLock.withLock {
            transform(flow.value).also { flow.value = it }
        }
    }
}
