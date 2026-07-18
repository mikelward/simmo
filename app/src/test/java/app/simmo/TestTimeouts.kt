package app.simmo

/**
 * Upper bound for a test's wait on the app's asynchronous state load — the
 * `stateHolders()`/`state` awaits that only complete once the store yields a
 * value. The isolated per-test store (TestSimmoApp) already guarantees that,
 * so this is a safety net: any future regression that stalls the load fails
 * in seconds with a clear timeout instead of hanging for minutes until the CI
 * job's own timeout. Generous on purpose — the passing path settles in
 * milliseconds even under heavy CPU contention.
 */
const val STATE_LOAD_TIMEOUT_MS = 30_000L
