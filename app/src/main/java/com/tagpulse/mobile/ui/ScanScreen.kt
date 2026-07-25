package com.tagpulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tagpulse.gateway.core.relay.DrainReport
import com.tagpulse.mobile.scan.ScanState

/**
 * The single-screen "Scan vehicle" flow (plan `docs/design/obdii-mve-plan.md` §8 M5).
 * Function over form: a **Scan vehicle** button plus a status/result area that renders
 * the pipeline [ScanState] (idle → connecting → handshaking → reading → relaying →
 * done/error), the decoded PIDs, and the relay outcome.
 *
 * Pure and state-driven — no I/O, no ViewModel here — so it previews and composes off
 * the [ScanState] the [com.tagpulse.mobile.scan.ScanCoordinator] exposes. The instrumented
 * UI test is HIL; the coordinator logic is the gate-covered part.
 *
 * @param state the current scan state to render.
 * @param onScan invoked when the operator taps **Scan vehicle**.
 */
@Composable
fun ScanScreen(
    state: ScanState,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "TagPulse — OBD-II", style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = onScan,
            enabled = !state.isInFlight(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Scan vehicle")
        }

        StatusCard(state = state)
    }
}

@Composable
private fun StatusCard(state: ScanState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Status", style = MaterialTheme.typography.titleMedium)
            Text(text = state.headline(), style = MaterialTheme.typography.bodyLarge)

            if (state.isInFlight()) {
                CircularProgressIndicator()
            }

            when (state) {
                is ScanState.Done -> DoneDetail(state)
                is ScanState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun DoneDetail(done: ScanState.Done) {
    Text(text = "PID snapshot", style = MaterialTheme.typography.titleSmall)
    if (done.pids.isEmpty()) {
        Text(text = "No PIDs decoded (all returned NO DATA).")
    } else {
        for ((key, value) in done.pids) {
            Text(text = "• $key = $value")
        }
    }
    Text(text = "GPS fix: ${if (done.hasLocation) "attached" else "none"}")
    Text(text = "Relay: ${done.report.relaySummary()}")
}

/** Whether a scan is currently running (the button is disabled + a spinner shows). */
private fun ScanState.isInFlight(): Boolean = when (this) {
    ScanState.Connecting, ScanState.Handshaking, ScanState.Reading, ScanState.Relaying -> true
    ScanState.Idle, is ScanState.Done, is ScanState.Error -> false
}

/** A short human headline per state. */
private fun ScanState.headline(): String = when (this) {
    ScanState.Idle -> "Ready — tap Scan vehicle."
    ScanState.Connecting -> "Connecting to the dongle…"
    ScanState.Handshaking -> "Handshaking (ELM327 init)…"
    ScanState.Reading -> "Reading PIDs…"
    ScanState.Relaying -> "Relaying to TagPulse…"
    is ScanState.Done -> "Done — read relayed."
    is ScanState.Error -> "Error"
}

private fun DrainReport.relaySummary(): String =
    "sent=$sent, failed=$failed, rejected=$rejected"

@Preview(showBackground = true)
@Composable
private fun ScanScreenIdlePreview() {
    MaterialTheme { ScanScreen(state = ScanState.Idle, onScan = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ScanScreenDonePreview() {
    MaterialTheme {
        ScanScreen(
            state = ScanState.Done(
                pids = linkedMapOf("rpm" to 850, "speed_kph" to 0, "coolant_temp_c" to 89, "fuel_level_pct" to 47.5),
                hasLocation = true,
                report = DrainReport(sent = 1, batches = 1),
            ),
            onScan = {},
        )
    }
}
