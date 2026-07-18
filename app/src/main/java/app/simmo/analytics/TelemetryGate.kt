package app.simmo.analytics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps telemetry collection in step with the persisted "Make Simmo better"
 * choice (SPEC "Permissions and privacy"): crash reporting and usage
 * analytics run only while the stored opt-in says so.
 *
 * The manifest disables Firebase's automatic collection, so on a fresh
 * install nothing is collected before the stored choice loads and is applied
 * here. Both SDKs persist the applied value themselves, so later process
 * starts honor the last-known choice even before DataStore has loaded.
 */
class TelemetryGate(private val setCollectionEnabled: (Boolean) -> Unit) {

    /** Applies [optIns] to the collection switches as it changes; never returns. */
    suspend fun follow(optIns: Flow<Boolean>) {
        optIns.distinctUntilChanged().collect { setCollectionEnabled(it) }
    }

    companion object {
        /**
         * The Firebase-backed gate, or null when this build carries no
         * Firebase config (no google-services.json at build time — see
         * SETUP.md): the SDKs are inert then and the Crashlytics accessor
         * would throw without an initialized [FirebaseApp].
         */
        fun firebase(context: Context): TelemetryGate? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            val analytics = FirebaseAnalytics.getInstance(context)
            val crashlytics = FirebaseCrashlytics.getInstance()
            return TelemetryGate { enabled ->
                analytics.setAnalyticsCollectionEnabled(enabled)
                crashlytics.setCrashlyticsCollectionEnabled(enabled)
                if (!enabled) {
                    // Crashlytics only honors the disable from the next
                    // launch, and already-captured reports stay on disk: drop
                    // them so an opt-out is effective now — and since the
                    // stored false re-applies here on every later launch, a
                    // crash captured in the tail of the opt-out session is
                    // deleted before a re-enable could ever upload it.
                    crashlytics.deleteUnsentReports()
                    // Unlink the analytics identity too, so a later re-enable
                    // starts a fresh app-instance ID instead of resuming the
                    // old stream.
                    analytics.resetAnalyticsData()
                }
            }
        }
    }
}
