package com.tagpulse.gateway.obdii.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scriptable, in-memory [BleTransport] for unit-testing
 * [com.tagpulse.gateway.obdii.elm.Elm327Session] **without hardware** (plan
 * `docs/design/obdii-mve-plan.md` §6 "scriptable BLE mock").
 *
 * For each command written (trimmed of its `\r` terminator) it emits the scripted
 * list of notification fragments — so a test can model both a clean single-frame
 * response and BLE fragmentation across several notifications. Commands with no
 * script entry emit nothing, which drives the session's per-command timeout path.
 *
 * All writes are recorded in [writes], so a test can assert the exact ELM327
 * command sequence/order.
 *
 * @param script command → ordered notification fragments to emit for it.
 * @param dropAfter if set, [write] throws [BleDisconnectedException] once this
 *   command is issued (models a mid-session GATT drop).
 */
class FakeBleTransport(
    private val script: Map<String, List<String>>,
    private val dropAfter: String? = null,
) : BleTransport {

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    /** Every command written, in order (terminator stripped). */
    val writes: MutableList<String> = mutableListOf()

    /** How many times [connect] was called (reconnect assertions). */
    var connectCount: Int = 0
        private set

    private var dropTriggered = false

    override suspend fun connect() {
        connectCount++
        _connected.value = true
    }

    override suspend fun write(command: ByteArray) {
        val cmd = String(command, Charsets.US_ASCII).trim()
        writes += cmd

        if (dropAfter != null && cmd == dropAfter && !dropTriggered) {
            dropTriggered = true
            _connected.value = false
            throw BleDisconnectedException()
        }

        script[cmd]?.forEach { fragment ->
            _notifications.emit(fragment.toByteArray(Charsets.US_ASCII))
        }
    }

    override suspend fun disconnect() {
        _connected.value = false
    }
}
