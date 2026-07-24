@file:Suppress("DEPRECATION") // Pre-API-33 GATT write overloads are used behind version guards.

package com.tagpulse.gateway.obdii.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * The real `android.bluetooth` implementation of [BleTransport] (plan
 * `docs/design/obdii-mve-plan.md` §6).
 *
 * **HIL-only.** This needs a physical dongle and BLE permissions, so it is *not*
 * unit-tested — [com.tagpulse.gateway.obdii.elm.Elm327Session] is exercised with
 * `FakeBleTransport` instead. It is kept thin and correct so it compiles + lints
 * clean and is ready for the hardware-in-the-loop M1 check.
 *
 * Flow: `BluetoothLeScanner` (filter by service UUID or name) → `connectGatt` →
 * request a larger MTU (best-effort) → `discoverServices` → resolve the
 * notify/write characteristics (from [config] or by discovery) → enable
 * notifications via the CCCD → ready. Notification fragments are surfaced on
 * [notifications] and reassembled by the session regardless of MTU.
 *
 * `@SuppressLint("MissingPermission")`: BLE runtime-permission handling is minimal
 * at M1 (plan §6) — the manifest declares the permissions; the caller is
 * responsible for having them granted before `connect()`.
 *
 * @param context application context.
 * @param config service/characteristic UUIDs; `null` fields are discovered (§6/§9).
 * @param deviceNamePrefix optional scan filter by advertised name (e.g. "OBDII").
 * @param mtu MTU to request after connect (not assumed granted — reassemble regardless).
 * @param stepTimeoutMs deadline for each connect step.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    private val context: Context,
    private val config: BleUuidConfig = BleUuidConfig.NORDIC_UART_LIKE,
    private val deviceNamePrefix: String? = null,
    private val mtu: Int = 247,
    private val stepTimeoutMs: Long = 10_000,
) : BleTransport {

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    // Per-step signals completed from the GATT/scan callbacks.
    @Volatile private var scanResult: CompletableDeferred<BluetoothDevice>? = null
    @Volatile private var servicesReady: CompletableDeferred<Unit>? = null
    @Volatile private var mtuReady: CompletableDeferred<Unit>? = null
    @Volatile private var notifyReady: CompletableDeferred<Unit>? = null

    override suspend fun connect() {
        val device = scanForDongle()
        withTimeout(stepTimeoutMs) {
            val services = CompletableDeferred<Unit>().also { servicesReady = it }
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            services.await()
        }
        val activeGatt = gatt ?: throw BleException("GATT unavailable after connect")

        // Best-effort MTU bump — do NOT assume it's granted (plan §6).
        withTimeout(stepTimeoutMs) {
            val ready = CompletableDeferred<Unit>().also { mtuReady = it }
            if (!activeGatt.requestMtu(mtu)) ready.complete(Unit)
            ready.await()
        }

        resolveCharacteristics(activeGatt)
        enableNotifications(activeGatt)
        _connected.value = true
    }

    override suspend fun write(command: ByteArray) {
        val activeGatt = gatt ?: throw BleDisconnectedException()
        if (!_connected.value) throw BleDisconnectedException()
        val target = writeChar ?: throw BleException("write characteristic not resolved")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(
                target,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            target.value = command
            activeGatt.writeCharacteristic(target)
        }
        if (!ok) throw BleException("characteristic write rejected")
    }

    override suspend fun disconnect() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeChar = null
        notifyChar = null
        _connected.value = false
    }

    private suspend fun scanForDongle(): BluetoothDevice {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw BleException("BLE scanner unavailable (adapter off?)")
        val filters = config.serviceUuid?.let {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
        } ?: emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return withTimeout(stepTimeoutMs) {
            val found = CompletableDeferred<BluetoothDevice>().also { scanResult = it }
            scanner.startScan(filters, settings, scanCallback)
            try {
                found.await()
            } finally {
                scanner.stopScan(scanCallback)
            }
        }
    }

    private fun resolveCharacteristics(activeGatt: BluetoothGatt) {
        val service = config.serviceUuid?.let { activeGatt.getService(it) }
            ?: activeGatt.services.firstOrNull { svc ->
                svc.characteristics.any { it.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY) } &&
                    svc.characteristics.any { it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE) || it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) }
            }
            ?: throw BleException("no ELM327-like GATT service found")

        notifyChar = config.notifyCharUuid?.let { service.getCharacteristic(it) }
            ?: service.characteristics.firstOrNull { it.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY) }
                ?: throw BleException("no notify characteristic found")

        writeChar = config.writeCharUuid?.let { service.getCharacteristic(it) }
            ?: service.characteristics.firstOrNull {
                it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE) ||
                    it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            }
                ?: throw BleException("no write characteristic found")
    }

    private suspend fun enableNotifications(activeGatt: BluetoothGatt) {
        val characteristic = notifyChar ?: throw BleException("notify characteristic not resolved")
        activeGatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(BleUuidConfig.CCCD_UUID)
            ?: throw BleException("notify characteristic has no CCCD")
        withTimeout(stepTimeoutMs) {
            val ready = CompletableDeferred<Unit>().also { notifyReady = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                activeGatt.writeDescriptor(cccd)
            }
            ready.await()
        }
    }

    private fun BluetoothGattCharacteristic.hasProperty(property: Int): Boolean =
        (properties and property) != 0

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val prefix = deviceNamePrefix
            if (prefix != null && result.scanRecord?.deviceName?.startsWith(prefix) != true) return
            scanResult?.takeIf { it.isActive }?.complete(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanResult?.completeExceptionally(BleException("BLE scan failed: $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                    servicesReady?.takeIf { it.isActive }
                        ?.completeExceptionally(BleDisconnectedException())
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesReady?.complete(Unit)
            } else {
                servicesReady?.completeExceptionally(BleException("service discovery failed: $status"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Grant is advisory; we reassemble regardless of the negotiated MTU.
            mtuReady?.complete(Unit)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notifyReady?.complete(Unit)
            } else {
                notifyReady?.completeExceptionally(BleException("CCCD write failed: $status"))
            }
        }

        // Deprecated overload retained for < API 33; still dispatched on newer levels.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == notifyChar?.uuid) {
                characteristic.value?.let { _notifications.tryEmit(it.copyOf()) }
            }
        }
    }
}
