package com.tagpulse.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tagpulse.mobile.enrol.EnrolState
import com.tagpulse.mobile.enrol.EnrolmentInput
import com.tagpulse.mobile.enrol.ProvisioningPayload

/**
 * The handset↔tenant enrolment form (ledger `C-RYH7`, Increment 1;
 * `docs/design/enrolment-flow.md`). The operator supplies the backend URL +
 * provisioning key (from the enrolment QR or manual entry) and pastes the tenant
 * `tp_` ingest key (masked), then taps **Provision**. The state/result area renders
 * the [EnrolState] (idle → provisioning → enrolled/error).
 *
 * Pure and state-driven — no I/O — so it previews and composes off the [EnrolState]
 * the `EnrolmentCoordinator` exposes. Field state is remembered locally; the instrumented
 * UI test is HIL, the coordinator logic is the gate-covered part.
 *
 * @param state the current enrolment state to render.
 * @param onEnrol invoked with the assembled [EnrolmentInput] when the operator taps **Provision**.
 * @param onScanQr if non-null, a **Scan QR** affordance is shown (Increment 1b wires the
 *   ML Kit scanner); null here means manual-entry only, with no dead button.
 * @param prefill optional values (e.g. from a QR scan) used to seed the URL + provisioning key.
 */
@Composable
fun EnrolScreen(
    state: EnrolState,
    onEnrol: (EnrolmentInput) -> Unit,
    modifier: Modifier = Modifier,
    onScanQr: (() -> Unit)? = null,
    prefill: ProvisioningPayload? = null,
) {
    var baseUrl by rememberSaveable(prefill) { mutableStateOf(prefill?.baseUrl ?: "") }
    var provisioningKey by rememberSaveable(prefill) { mutableStateOf(prefill?.provisioningKey ?: "") }
    var ingestApiKey by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Enrol this handset", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Scan the enrolment QR or enter the details, then paste the ingest key.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (onScanQr != null) {
            OutlinedButton(
                onClick = onScanQr,
                enabled = !state.isInFlight(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Scan enrolment QR")
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Backend URL (https://…)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = provisioningKey,
            onValueChange = { provisioningKey = it },
            label = { Text("Provisioning key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ingestApiKey,
            onValueChange = { ingestApiKey = it },
            label = { Text("Ingest API key (tp_…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                onEnrol(EnrolmentInput(baseUrl, provisioningKey, ingestApiKey, deviceName))
            },
            enabled = !state.isInFlight(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Provision")
        }

        StatusCard(state = state)
    }
}

@Composable
private fun StatusCard(state: EnrolState, modifier: Modifier = Modifier) {
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
                is EnrolState.Enrolled -> Text(
                    text = "Device ${state.deviceId} (${state.status}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is EnrolState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
        }
    }
}

private fun EnrolState.isInFlight(): Boolean = this is EnrolState.Provisioning

private fun EnrolState.headline(): String = when (this) {
    EnrolState.Idle -> "Enter the enrolment details."
    EnrolState.Provisioning -> "Provisioning…"
    is EnrolState.Enrolled -> "Enrolled — you can start scanning."
    is EnrolState.Error -> "Enrolment problem"
}

@Preview(showBackground = true)
@Composable
private fun EnrolScreenIdlePreview() {
    MaterialTheme { EnrolScreen(state = EnrolState.Idle, onEnrol = {}) }
}

@Preview(showBackground = true)
@Composable
private fun EnrolScreenErrorPreview() {
    MaterialTheme {
        EnrolScreen(
            state = EnrolState.Error(EnrolState.ErrorKind.INPUT, "The backend URL must be a valid https:// address."),
            onEnrol = {},
        )
    }
}
