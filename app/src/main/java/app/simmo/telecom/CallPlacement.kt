package app.simmo.telecom

import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import app.simmo.SimmoApp
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
        false
    }
}

private const val TAG = "SimmoCallPlacement"
