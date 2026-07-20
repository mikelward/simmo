package app.simmo.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import app.simmo.SimmoApp
import app.simmo.SimmoDebugLog
import app.simmo.redactAccountId
import app.simmo.domain.PassToken
import app.simmo.domain.PhoneAccountRef

/** How long a re-placed call has to reach the redirection service. */
const val REPLACE_PASS_TOKEN_TTL_MILLIS = 30_000L

/**
 * Re-places [handle] on [account] after a cancel-and-re-place flow (the
 * chooser, the delayed call): mints the loop-guard pass token first — the new
 * call consults the redirection service immediately and must pass through
 * unmodified instead of looping (SPEC "Redirect-loop guard") — then places
 * via TelecomManager with the account pinned. Returns false when the
 * account's platform handle is gone (the SIM vanished since the verdict) or
 * placing threw (CALL_PHONE revoked concurrently); the original call is
 * already canceled by then, so the caller decides how to surface it.
 */
fun SimmoApp.replaceCall(handle: Uri, account: PhoneAccountRef): Boolean {
    passTokens.add(
        PassToken(
            dialedNumber = handle.schemeSpecificPart.orEmpty(),
            account = account,
            expiresAtMillis = System.currentTimeMillis() + REPLACE_PASS_TOKEN_TTL_MILLIS,
        ),
    )
    val accountHandle = assembler.handleFor(account)
    if (accountHandle == null) {
        Log.e(TAG, "Chosen phone account no longer available; not re-placing")
        SimmoDebugLog.warning("Re-place skipped: chosen phone account (${redactAccountId(account.id)}) no longer available")
        return false
    }
    val extras = Bundle().apply {
        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
    }
    return try {
        getSystemService(TelecomManager::class.java).placeCall(handle, extras)
        true
    } catch (e: SecurityException) {
        Log.e(TAG, "Re-placing the call failed", e)
        SimmoDebugLog.warning("Re-placing the call failed (CALL_PHONE revoked?)", e)
        false
    }
}

/**
 * [replaceCall], with the shared last-resort recovery when it fails: the
 * original call was already canceled, and the target (a SIM's or a calling
 * account's handle) is gone or `CALL_PHONE` was revoked — so rather than
 * stranding the call, drop the user in the dialer with the number, minting a
 * wildcard pass token first so the redial proceeds on whatever the dialer
 * picks instead of re-entering the still-failing rule (SPEC "Redirect-loop
 * guard"). Used by the chooser and the delayed-call countdown.
 */
fun SimmoApp.replaceCallOrOpenDialer(activity: Context, handle: Uri, account: PhoneAccountRef) {
    if (replaceCall(handle, account)) return
    passTokens.add(
        PassToken(
            dialedNumber = handle.schemeSpecificPart.orEmpty(),
            account = null,
            expiresAtMillis = System.currentTimeMillis() + REPLACE_PASS_TOKEN_TTL_MILLIS,
        ),
    )
    runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, handle)) }
}

private const val TAG = "SimmoCallPlacement"
