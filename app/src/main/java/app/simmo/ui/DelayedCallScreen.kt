package app.simmo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.simmo.R

/**
 * Everything the delayed-call countdown renders, prepared up front from the
 * launch intent and the warm snapshot (like the chooser, it appears
 * mid-call-attempt and must paint its first frame instantly).
 */
data class DelayedCallUiState(
    /** The rule-picked target's name — a SIM ("Telstra AU") or a calling account ("SIP work"). */
    val targetLabel: String,
    val dialedNumber: String,
    /** "+61 Australia" when the destination is determined; null otherwise. */
    val countryLabel: String?,
    val remainingSeconds: Int,
)

/**
 * The delay-before-calling countdown (SPEC "Call feedback and delay"): which
 * SIM or calling account the rule picked, the number and destination, and the
 * seconds left before the call goes out — with "Call now" to skip the wait and
 * "Cancel call" to abandon it (the carrier call was already canceled before
 * this opened).
 */
@Composable
internal fun DelayedCallContent(
    state: DelayedCallUiState,
    onCallNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.calling_using, state.targetLabel),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = state.dialedNumber,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                state.countryLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.remainingSeconds > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.delayed_call_countdown,
                            state.remainingSeconds,
                            state.remainingSeconds,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            Button(
                onClick = onCallNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.delayed_call_now))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.chooser_cancel))
            }
        }
    }
}
