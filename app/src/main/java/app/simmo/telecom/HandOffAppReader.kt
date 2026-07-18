package app.simmo.telecom

import android.content.pm.PackageManager
import app.simmo.domain.DialHandoffApp

/**
 * The dial-intent hand-off targets currently installed (SPEC "Hand-off to another
 * app"). A PackageManager query — run off the main thread and off the decision
 * path; the result feeds the warm snapshot's `handOffApps`. Needs a `<queries>`
 * entry per package in the manifest for Android 11+ package visibility.
 */
fun installedDialHandoffApps(packageManager: PackageManager): Set<DialHandoffApp> =
    DialHandoffApp.entries.filterTo(mutableSetOf()) { app ->
        runCatching { packageManager.getPackageInfo(app.packageName, 0) }.isSuccess
    }
