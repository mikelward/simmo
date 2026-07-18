package app.simmo.telecom

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.simmo.domain.DialHandoffApp

/**
 * The dial-intent hand-off targets whose number-carrying launch intent actually
 * resolves (SPEC "Hand-off to another app"; docs/handoff-intents.md "reachability
 * discovery"). Resolving the real intent — not just checking the package exists —
 * keeps an installed app that can't receive its deep link (e.g. a Yolla build
 * without a `tel:` handler) out of the editor, so users can't save a rule that
 * would always fall through at call time. A PackageManager query — run off the
 * main thread and off the decision path; the result feeds the warm snapshot's
 * `handOffApps`. Needs a `<queries>` entry per package in the manifest for
 * Android 11+ package visibility.
 */
fun installedDialHandoffApps(packageManager: PackageManager): Set<DialHandoffApp> =
    DialHandoffApp.entries.filterTo(mutableSetOf()) { app ->
        // Resolution matches on scheme/host/path only, so any well-formed
        // number probes the same filters the real call-time intent will hit.
        Intent(Intent.ACTION_VIEW, Uri.parse(app.launchUri(PROBE_E164)))
            .setPackage(app.packageName)
            .resolveActivity(packageManager) != null
    }

private const val PROBE_E164 = "+12125550123"
