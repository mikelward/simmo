package app.simmo.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.simmo.MainActivity

/**
 * The Quick Settings tile (SPEC "Quick Settings tile"): a shortcut into
 * Simmo's SIMs screen, which in turn links to the system SIM settings.
 * Enabling or disabling a SIM needs carrier privileges, so a toggle tile is
 * impossible for a regular app — a fast path into the management screens is
 * the whole job, and the tile carries no state of its own.
 */
class ManageSimsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // A launcher-style tile is always ready; without this some devices
        // render the default STATE_INACTIVE dimmed, reading as "off".
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            // The SIMs screen shows carrier and SIM names, so it is not
            // lock-screen safe: prompt for unlock first (TileService's own
            // guidance), instead of leaving keyguard timing to the launch.
            unlockAndRun(::launchManageSims)
        } else {
            launchManageSims()
        }
    }

    private fun launchManageSims() {
        val intent = manageSimsIntent(this)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    companion object {
        /**
         * Launches the SIMs screen from outside the app's own task. SINGLE_TOP
         * so a foreground MainActivity is reused via onNewIntent — and
         * deliberately no CLEAR_TOP: the Ask chooser can sit above
         * MainActivity mid-call-attempt, and clearing it would silently drop
         * the user's canceled-and-not-yet-re-placed call (Codex on PR #22).
         * With the chooser up, the registry opens above it and Back returns
         * to the chooser.
         */
        fun manageSimsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_MANAGE_SIMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
