package app.simmo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.simmo.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * Open-source attribution (SPEC "Call feedback and delay" → the Settings foot):
 * the transitive dependency graph and its licenses, reached from Settings. The
 * list is read from the committed `res/raw/aboutlibraries.json`, regenerated
 * with `./gradlew :app:exportLibraryDefinitions` — the AboutLibraries plugin
 * can't wire the resource in automatically under AGP 9 (see app/build.gradle.kts).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // rememberLibraries parses the bundled JSON off the composition thread and
    // swaps it in when ready, so the screen renders its title instantly.
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    LicensesContent(libraries)
}

@Composable
internal fun LicensesContent(libraries: Libs?) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Text(
                text = stringResource(R.string.settings_licenses_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp),
            )
            // LibrariesContainer brings its own scrolling list; the horizontal
            // inset matches the title so entries line up with it.
            LibrariesContainer(
                libraries,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
