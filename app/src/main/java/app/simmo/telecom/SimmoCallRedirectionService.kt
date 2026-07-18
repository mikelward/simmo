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
import app.simmo.domain.PassToken
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

        // Cancel-and-forward to a calling app.
        //
        // We can't wait to confirm the launch before responding: the Telecom
        // deadline is hard, and `startActivity` doesn't reliably report success
        // anyway (a blocked background launch only logs). So the response is never
        // gated on the launch.
        //
        // Resolve *before* responding (this PackageManager IPC must not run after
        // the watchdog is removed): a target that can't take the hand-off — gone,
        // or its deep link unhandled — places the call unmodified and posts a
        // "couldn't open <app>" notification. This catches the common failure.
        //
        // Otherwise respond with `cancelCall()` *first*, then launch — so a slow
        // launch can't miss the deadline, and cancelCall being the single latched
        // response rules out opening the app on top of a still-proceeding call. A
        // launch that then throws can't be un-cancelled, so surface it (Redial);
        // a silently blocked launch (BAL) can't be detected — still the device-QA
        // / full-screen-intent question in docs/qa-matrix.md (TODO.md).
        fun handOff(intent: Intent, appLabel: String, packageName: String) {
            val number = handle.schemeSpecificPart.orEmpty()
            fun notifyFailed(placed: Boolean) = app.notifications.postHandOffFailed(
                appLabel,
                packageName,
                number,
                placed,
            )
            if (intent.resolveActivity(packageManager) == null) {
                respond { placeCallUnmodified() }
                notifyFailed(placed = true)
                return
            }
            respond {
                cancelCall()
                if (runCatching { startActivity(intent) }.isFailure) {
                    // The call is already cancelled and the app didn't open.
                    // Notifications are optional, so surface it a second,
                    // permission-free way too: drop the user in the dialer with
                    // the number so they can retry even with notifications off.
                    // (If the launch was blocked by BAL rather than a per-app
                    // failure, this is likewise blocked — best effort.)
                    notifyFailed(placed = false)
                    // Mint a short-lived pass token first: placing the call from
                    // the recovery dialer would otherwise re-enter this same
                    // still-failing hand-off rule and cancel again, looping. The
                    // dialer doesn't pin a SIM, so the token matches any account
                    // (null) — whichever SIM the redial goes out on passes through
                    // unmodified (SPEC "Redirect-loop guard").
                    app.passTokens.add(
                        PassToken(
                            dialedNumber = number,
                            account = null,
                            expiresAtMillis = System.currentTimeMillis() + PASS_TOKEN_TTL_MILLIS,
                        ),
                    )
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_DIAL, handle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
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
                    // In-memory ref (a string derived from the handle — no IPC).
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

                is Verdict.ForwardToApp -> handOff(
                    // The app's number-carrying deep link (e.g. Google Voice, Teams).
                    Intent(Intent.ACTION_VIEW, Uri.parse(verdict.uri))
                        .setPackage(verdict.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    verdict.appLabel,
                    verdict.packageName,
                )

                is Verdict.ForwardToContactApp -> handOff(
                    // The contact's per-contact call row (e.g. WhatsApp). The MIME
                    // type selects the app's call action; ACTION_VIEW on the bare
                    // URI would resolve to the generic contact viewer instead.
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            ContentUris.withAppendedId(
                                ContactsContract.Data.CONTENT_URI,
                                verdict.dataRowId,
                            ),
                            verdict.mimeType,
                        )
                        .setPackage(verdict.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    verdict.appLabel,
                    verdict.packageName,
                )

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

        /** Recovery-dialer loop guard; matches the chooser's re-place window. */
        const val PASS_TOKEN_TTL_MILLIS = 30_000L
    }
}
