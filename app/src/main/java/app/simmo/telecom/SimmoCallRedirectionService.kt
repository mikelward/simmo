package app.simmo.telecom

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import app.simmo.SimmoApp
import app.simmo.domain.PlacedCall
import app.simmo.domain.Verdict
import app.simmo.ui.ChooserActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * The interception hook (SPEC "How it hooks into Android"). Telecom CANCELS
 * the call if no response arrives within ~5 s, so this class guarantees
 * exactly one explicit response per call on every path: a watchdog answers
 * `placeCallUnmodified()` if the decision hasn't responded by [WATCHDOG_MILLIS],
 * and every failure path degrades to the same. The decision itself runs off
 * the main thread against the in-memory snapshot only.
 */
class SimmoCallRedirectionService : CallRedirectionService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        val app = application as SimmoApp
        val responded = AtomicBoolean(false)
        // Scoped to this call: concurrent redirections each get their own
        // watchdog, and finishing one must not cancel another's fallback.
        val watchdogToken = Any()
        fun respond(action: () -> Unit) {
            if (responded.compareAndSet(false, true)) {
                mainHandler.removeCallbacksAndMessages(watchdogToken)
                try {
                    action()
                } catch (e: RuntimeException) {
                    // The response call itself failed (e.g. a stale handle);
                    // responded is already latched, so the watchdog can't save
                    // us — try the safe response directly as a last resort.
                    Log.e(TAG, "Redirection response failed; placing unmodified", e)
                    runCatching { placeCallUnmodified() }
                }
            }
        }

        mainHandler.postDelayed(
            { respond { placeCallUnmodified() } },
            watchdogToken,
            WATCHDOG_MILLIS,
        )

        app.appScope.launch {
            val verdict = app.coordinator.decide(
                PlacedCall(
                    dialedNumber = handle.schemeSpecificPart.orEmpty(),
                    currentAccount = app.assembler.refFor(initialPhoneAccount),
                    interactive = allowInteractiveResponse,
                ),
            )
            when (verdict) {
                is Verdict.Proceed -> {
                    verdict.consumedToken?.let(app.passTokens::consume)
                    respond { placeCallUnmodified() }
                }

                is Verdict.RedirectToAccount -> {
                    val target = app.assembler.handleFor(verdict.account)
                    if (target != null) {
                        respond { redirectCall(handle, target, /* confirmFirst = */ false) }
                    } else {
                        // The account vanished between snapshot refreshes;
                        // never gamble with the user's call.
                        respond { placeCallUnmodified() }
                    }
                }

                is Verdict.ForwardToApp -> respond {
                    cancelCall()
                    startActivity(
                        Intent(Intent.ACTION_DIAL, handle)
                            .setPackage(verdict.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }

                is Verdict.ForwardToContactApp -> {
                    // Place the call to the contact via the app (e.g. WhatsApp)
                    // by ACTION_VIEW-ing its Data row. Resolve *before* latching a
                    // response: this PackageManager IPC must not run after respond()
                    // removes the watchdog, or a stall could eat Telecom's deadline.
                    // While it's unresolved the watchdog is still armed and will
                    // place the call unmodified. If the app can't take it
                    // (uninstalled since the snapshot), never strand the call.
                    val dataUri =
                        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, verdict.dataRowId)
                    val intent = Intent(Intent.ACTION_VIEW)
                        // The MIME type is what selects the app's call action on
                        // the contact row; ACTION_VIEW on the bare URI resolves to
                        // the generic contact viewer instead.
                        .setDataAndType(dataUri, verdict.mimeType)
                        .setPackage(verdict.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(packageManager) != null) {
                        respond {
                            cancelCall()
                            startActivity(intent)
                        }
                    } else {
                        respond { placeCallUnmodified() }
                    }
                }

                // The chooser re-places the call and mints the pass token
                // (SPEC "The chooser (Ask flow)"). Background-activity-launch
                // rules can silently swallow startActivity on some Android
                // versions; whether the redirection binding exempts us needs
                // the on-device QA pass (docs/qa-matrix.md) — if it doesn't,
                // this must move to a full-screen-intent notification.
                is Verdict.OpenChooser -> respond {
                    cancelCall()
                    startActivity(
                        ChooserActivity
                            .launchIntent(this@SimmoCallRedirectionService, handle, verdict.skippedInactiveSims)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "SimmoRedirection"
        const val WATCHDOG_MILLIS = 3_000L
    }
}
