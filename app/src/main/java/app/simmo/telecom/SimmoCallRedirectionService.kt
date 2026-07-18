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
import app.simmo.ui.DelayedCallActivity
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
        // Returns true only when THIS action won the race and completed —
        // callers announcing what happened (the "Calling using" toast) must
        // not claim an action the watchdog preempted or that fell back to
        // placeCallUnmodified (Codex on PR #38).
        fun respond(action: () -> Unit): Boolean {
            if (!responded.compareAndSet(false, true)) return false
            mainHandler.removeCallbacksAndMessages(watchdogToken)
            return try {
                action()
                true
            } catch (e: RuntimeException) {
                // The response call itself failed (e.g. a stale handle);
                // responded is already latched, so the watchdog can't save
                // us — try the safe response directly as a last resort.
                Log.e(TAG, "Redirection response failed; placing unmodified", e)
                runCatching { placeCallUnmodified() }
                false
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
        fun handOff(intent: Intent, appLabel: String, packageName: String, announce: Boolean) {
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
                if (runCatching { startActivity(intent) }.isSuccess) {
                    // The optional "Calling using <app>" announcement — only
                    // once the launch has been sent; a failed hand-off shows
                    // its failure notice instead, never both.
                    if (announce) app.notifications.toastCallingUsing(appLabel)
                } else {
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
            val placedCall = PlacedCall(
                dialedNumber = handle.schemeSpecificPart.orEmpty(),
                // In-memory ref (a string derived from the handle — no IPC).
                currentAccount = app.assembler.refFor(initialPhoneAccount),
                interactive = allowInteractiveResponse,
            )
            val decision = app.coordinator.decide(placedCall)
            when (val verdict = decision.verdict) {
                is Verdict.Proceed -> {
                    verdict.consumedToken?.let(app.passTokens::consume)
                    // Toast only after responding (it must never delay the
                    // answer) and only if this response actually landed.
                    if (respond { placeCallUnmodified() }) {
                        verdict.announceTarget?.let(app.notifications::toastCallingUsing)
                    }
                }

                is Verdict.RedirectToAccount -> {
                    val target = app.assembler.handleFor(verdict.account)
                    if (target != null) {
                        // A same-contact correction rides along as a new
                        // handle; account and number change in one redirect.
                        val newHandle = verdict.newNumber
                            ?.let { Uri.fromParts("tel", it, null) }
                            ?: handle
                        if (respond { redirectCall(newHandle, target, /* confirmFirst = */ false) }) {
                            verdict.announceTarget?.let(app.notifications::toastCallingUsing)
                        }
                    } else {
                        // The account vanished between snapshot refreshes;
                        // never gamble with the user's call.
                        respond { placeCallUnmodified() }
                    }
                }

                // Same-contact number correction without a SIM change: only
                // the number is rewritten; the platform's own account stays.
                is Verdict.RedirectNumber -> respond {
                    redirectCall(
                        Uri.fromParts("tel", verdict.newNumberE164, null),
                        initialPhoneAccount,
                        /* confirmFirst = */ false,
                    )
                }

                // Delay before calling: cancel and show the countdown screen,
                // which re-places on the SIM (or recovers via the dialer if
                // the SIM's handle vanishes mid-countdown). The response is
                // never delayed — only the re-place is. Same BAL caveat as
                // the chooser's startActivity below.
                is Verdict.DelayedRedirect -> {
                    // Same stale-account guard as the redirect branch above
                    // (Codex on PR #38): if the SIM vanished between the
                    // snapshot and now, proceeding unmodified beats canceling
                    // into a countdown whose only recovery is the dialer.
                    if (app.assembler.handleFor(verdict.account) == null) {
                        respond { placeCallUnmodified() }
                    } else {
                        respond {
                            cancelCall()
                            startActivity(
                                DelayedCallActivity
                                    .launchIntent(this@SimmoCallRedirectionService, handle, verdict)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }

                is Verdict.ForwardToApp -> handOff(
                    // The app's number-carrying deep link (e.g. Google Voice, Teams).
                    Intent(Intent.ACTION_VIEW, Uri.parse(verdict.uri))
                        .setPackage(verdict.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    verdict.appLabel,
                    verdict.packageName,
                    verdict.announce,
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
                    verdict.announce,
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
                            .launchIntent(
                                this@SimmoCallRedirectionService,
                                handle,
                                verdict.skippedInactiveSims,
                                verdict.numberCorrection,
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }

                // The opt-in hands-free call guard (SPEC "Hands-free and
                // Android Auto safeguards"): the ONLY sanctioned drop, and it
                // is never silent — the notification (or its toast fallback)
                // always says what was stopped and reopens the call in the
                // chooser. Posted only when this cancel actually won the
                // watchdog race: a preempted response means the call
                // proceeded, and claiming a block would be a lie.
                is Verdict.BlockCall -> {
                    if (respond { cancelCall() }) {
                        app.notifications.postCallBlocked(verdict, handle)
                    }
                }
            }
            // A correction that existed but could neither be confirmed nor
            // applied (hands-free with a shared line or several local
            // numbers): the call above went out as dialed — offer the local
            // number by notification instead of staying silent (maintainer
            // direction). After the response, like the toasts; the in-flight
            // call is never touched. Computed with the verdict from the SAME
            // snapshot (Codex on PR #44, twice over): the engine's pass-token
            // guard sees the token the decision saw — consumption above can't
            // defeat it — and a telephony refresh landing mid-call can't
            // reclassify the correction as chooser-confirmable and swallow it.
            // Not for a blocked call: it did NOT go out as dialed, and the
            // guard notification's chooser already carries the correction.
            if (decision.verdict !is Verdict.BlockCall) {
                decision.missedCorrection?.let { missed ->
                    app.notifications.postLocalNumberOffer(missed, handle)
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
