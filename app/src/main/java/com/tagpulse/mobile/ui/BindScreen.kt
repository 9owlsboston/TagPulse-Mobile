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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tagpulse.mobile.bind.BindState

/**
 * The vehicle VIN-bind form (ledger `C-RYH7`, Increment 2a; `docs/design/vehicle-bind-flow.md`).
 * The operator enters/keys a **VIN** and taps **Look up vehicle**; on a match the resolved
 * **plate** is shown with a **Confirm this vehicle** button so the operator visually confirms
 * the plate matches the vehicle. Manual entry only in 2a (OBD-II Mode 09 auto-read = 2b, VIN
 * barcode = 2c).
 *
 * Pure and state-driven off [BindState].
 *
 * @param state the current bind state to render.
 * @param onResolve invoked with the raw VIN when the operator taps **Look up vehicle**.
 * @param onConfirm invoked when the operator confirms the shown plate/vehicle.
 * @param onReadFromVehicle if non-null, a **Read VIN from vehicle** affordance is shown
 *   (OBD-II Mode 09 auto-read, Increment 2b); null hides it (manual VIN entry only).
 */
@Composable
fun BindScreen(
    state: BindState,
    onResolve: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onReadFromVehicle: (() -> Unit)? = null,
) {
    var vin by rememberSaveable { mutableStateOf("") }
    val busy = state is BindState.Resolving || state is BindState.Reading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Bind a vehicle", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Read the VIN from the vehicle or enter it, then confirm the license plate.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (onReadFromVehicle != null) {
            OutlinedButton(
                onClick = onReadFromVehicle,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Read VIN from vehicle")
            }
        }

        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it },
            label = { Text("VIN (17 characters)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onResolve(vin) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Look up vehicle")
        }

        if (state is BindState.Confirming) {
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Confirm this vehicle (${state.plate})")
            }
        }

        StatusCard(state = state)
    }
}

@Composable
private fun StatusCard(state: BindState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Status", style = MaterialTheme.typography.titleMedium)
            Text(text = state.headline(), style = MaterialTheme.typography.bodyLarge)

            if (state is BindState.Resolving || state is BindState.Reading) {
                CircularProgressIndicator()
            }

            when (state) {
                is BindState.Confirming -> Text(
                    text = "Plate on file: ${state.plate}. Confirm it matches the vehicle.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is BindState.Bound -> Text(
                    text = "Bound to ${state.plate} (${state.vin}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is BindState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
        }
    }
}

private fun BindState.headline(): String = when (this) {
    BindState.Idle -> "Read or enter the vehicle VIN."
    BindState.Reading -> "Reading the VIN from the vehicle…"
    BindState.Resolving -> "Looking up the vehicle…"
    is BindState.Confirming -> "Vehicle found — confirm the plate."
    is BindState.Bound -> "Vehicle bound."
    is BindState.Error -> "Bind problem"
}

@Preview(showBackground = true)
@Composable
private fun BindScreenIdlePreview() {
    MaterialTheme { BindScreen(state = BindState.Idle, onResolve = {}, onConfirm = {}) }
}

@Preview(showBackground = true)
@Composable
private fun BindScreenConfirmingPreview() {
    MaterialTheme {
        BindScreen(
            state = BindState.Confirming("1HGCM82633A004352", "MASS-1234", "asset-9"),
            onResolve = {},
            onConfirm = {},
        )
    }
}
