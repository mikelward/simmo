package app.simmo.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.simmo.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

/**
 * Open-source attribution (SPEC "Call feedback and delay" → the Settings foot):
 * the transitive dependency graph and its licenses, reached from Settings. The
 * list is read from the committed `res/raw/aboutlibraries.json`, regenerated
 * with `./gradlew :app:exportBundledLicenses` — the AboutLibraries plugin
 * can't wire the resource in automatically under AGP 9 (see app/build.gradle.kts).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    // rememberLibraries parses the bundled JSON off the composition thread and
    // swaps it in when ready, so the screen renders its title instantly.
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    LicensesContent(
        libraries = libraries,
        onBack = onBack,
        onOpenLicenseUrl = { url ->
            // No browser (or a stripped device) shouldn't crash the screen —
            // same guard as the Settings privacy-policy link.
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        },
    )
}

@Composable
internal fun LicensesContent(
    libraries: Libs?,
    onBack: () -> Unit = {},
    onOpenLicenseUrl: (String) -> Unit = {},
) {
    // The tapped library's stable id, if any — its details fill the dialog
    // below. Saved (not a plain remember) so an open dialog survives rotation
    // and process death; resolved back to the library once the list is loaded.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(libraries, selectedId) {
        selectedId?.let { id -> libraries?.libraries?.firstOrNull { it.uniqueId == id } }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Title with a Back button at the end, matching the rules and
            // Country groups sub-screens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_licenses_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
            // Just the component names, one compact row each; the version and
            // license live behind a tap so the list stays scannable.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(
                    items = libraries?.libraries.orEmpty(),
                    key = { it.uniqueId },
                ) { library ->
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedId = library.uniqueId }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    selected?.let { library ->
        LibraryDetailsDialog(
            library = library,
            onOpenLicenseUrl = onOpenLicenseUrl,
            onDismiss = { selectedId = null },
        )
    }
}

/**
 * Version and license(s) for a tapped [library]. The bundled export carries no
 * license text (it's excluded to keep CI's regenerate-and-diff deterministic —
 * see app/build.gradle.kts), so each license with a URL is a link to the full
 * text rather than inline body copy.
 */
@Composable
private fun LibraryDetailsDialog(
    library: Library,
    onOpenLicenseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
        title = { Text(library.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                library.artifactVersion?.let { version ->
                    Text(
                        text = stringResource(R.string.settings_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                library.licenses.forEach { license ->
                    val url = license.url
                    if (!url.isNullOrEmpty()) {
                        // A link to the full license text — primary color and a
                        // tap target signal it opens in the browser.
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLicenseUrl(url) }
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
