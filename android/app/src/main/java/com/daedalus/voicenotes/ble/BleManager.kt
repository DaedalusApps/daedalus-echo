package com.daedalus.voicenotes.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// State definitions
// ---------------------------------------------------------------------------

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

data class BleState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceSerial: String = "",
    val fwVersion: String = "",
    val batteryPct: Int = 0,
    val storageFreeKb: Long = 0,
    val storageTotalKb: Long = 0,
    val isRecording: Boolean = false,
    val files: List<FileEntry> = emptyList(),
    val errorMessage: String = ""
)

// ---------------------------------------------------------------------------
// BleManager
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    // ------------------------------------------------------------------
    // Public state
    // ------------------------------------------------------------------

    private val _bleState = MutableStateFlow(BleState())
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var leScanner: BluetoothLeScanner? = null
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Single-consumer channel; responses flow here from onCharacteristicChanged. */
    private val responseChannel = Channel<ParsedResponse>(capacity = Channel.UNLIMITED)

    /** Signals completion of each writeDescriptor call (one per notification enable). */
    private val descriptorChannel = Channel<Unit>(capacity = Channel.UNLIMITED)

    // Descriptor UUID required to enable notifications on Android
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ------------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------------

    fun startScan() {
        _bleState.update { it.copy(connectionState = ConnectionState.SCANNING, errorMessage = "") }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
        leScanner = btManager.adapter?.bluetoothLeScanner

        val filter = ScanFilter.Builder()
            .setDeviceName("FW920")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        leScanner?.stopScan(scanCallback)
        leScanner = null
        if (_bleState.value.connectionState == ConnectionState.SCANNING) {
            _bleState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _bleState.update {
                it.copy(
                    connectionState = ConnectionState.ERROR,
                    errorMessage    = "Scan failed: error $errorCode"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        _bleState.update { it.copy(connectionState = ConnectionState.CONNECTING) }
        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    // ------------------------------------------------------------------
    // Disconnect
    // ------------------------------------------------------------------

    fun disconnect() {
        stopPoller()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeChar     = null
        _bleState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
    }

    // ------------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopPoller()
                    writeChar = null
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    _bleState.update {
                        it.copy(connectionState = ConnectionState.DISCONNECTED)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage    = "Service discovery failed: $status"
                    )
                }
                return
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            if (service == null) {
                _bleState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage    = "FW920 service not found"
                    )
                }
                return
            }

            writeChar = service.getCharacteristic(UUID.fromString(WRITE_UUID))

            // Enable notifications one at a time (GATT requires serialized operations),
            // then start the init sequence only after all descriptors are written.
            scope.launch {
                for (notifyUuid in listOf(NOTIFY_B0B2_UUID, NOTIFY_B0B3_UUID, NOTIFY_B0B4_UUID)) {
                    val notifyChar = service.getCharacteristic(UUID.fromString(notifyUuid)) ?: continue
                    enableNotification(gatt, notifyChar)
                    // Wait up to 2s for onDescriptorWrite before continuing to the next one
                    withTimeoutOrNull(2000L) { descriptorChannel.receive() }
                }
                Log.i("BleManager", "All notifications enabled, starting init sequence")
                runInitSequence()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i("BleManager", "MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("BleManager", "onDescriptorWrite status=$status char=${descriptor.characteristic.uuid}")
            descriptorChannel.trySend(Unit)
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    // ------------------------------------------------------------------
    // Notification helper
    // ------------------------------------------------------------------

    private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    // ------------------------------------------------------------------
    // Incoming data handler
    // ------------------------------------------------------------------

    private fun handleIncoming(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d("BleManager", "RX [${data.size}b]: $hex")
        val parsed = parseResponse(data) ?: return
        Log.d("BleManager", "RX parsed: $parsed")
        
        // Eagerly update state based on parsed response
        when (parsed) {
            is ParsedResponse.Serial  -> _bleState.update { it.copy(deviceSerial = parsed.value) }
            is ParsedResponse.FwVersion -> _bleState.update { it.copy(fwVersion = parsed.value) }
            is ParsedResponse.Status  -> mergeStatus(parsed)
            is ParsedResponse.RecordingStarted -> _bleState.update { it.copy(isRecording = true) }
            is ParsedResponse.RecordingStopped -> _bleState.update { it.copy(isRecording = false) }
            else -> Unit
        }
        responseChannel.trySend(parsed)
    }

    private fun mergeStatus(resp: ParsedResponse.Status) {
        val s = resp.status
        _bleState.update { current ->
            current.copy(
                batteryPct     = if (s.batteryPct > 0) s.batteryPct else current.batteryPct,
                storageFreeKb  = if (s.storageFreeKb > 0) s.storageFreeKb else current.storageFreeKb,
                storageTotalKb = if (s.storageTotalKb > 0) s.storageTotalKb else current.storageTotalKb,
                // Never update isRecording from status polls — only CMD 0x06/0x08 events are reliable.
                isRecording    = current.isRecording,
                fwVersion      = if (s.fwName.isNotEmpty()) s.fwName else current.fwVersion
            )
        }
    }

    // Extend BleState to carry fwName without breaking the public contract
    private var BleState.fwName: String
        get()      = fwVersion
        set(value) { /* intentionally no-op; used only in mergeStatus lambda */ }

    // ------------------------------------------------------------------
    // Init sequence
    // ------------------------------------------------------------------

    private suspend fun runInitSequence() {
        Log.i("BleManager", "runInitSequence: start")

        // 1. CMD 0x02 — get firmware version
        sendAndAwait(PKT_GET_FW_VERSION, expectedCmd = 0x02)
            .also { Log.d("BleManager", "CMD 0x02 (fw version): ${if (it != null) "ok" else "timeout"}") }

        // 2. CMD 0x03 — set firmware version string
        sendAndAwait(buildSetFwVersion(), expectedCmd = 0x03)
            .also { Log.d("BleManager", "CMD 0x03 (set fw): ${if (it != null) "ok" else "timeout"}") }

        // 3. CMD 0x01 — get serial
        sendAndAwait(PKT_GET_SERIAL, expectedCmd = 0x01)
            .also { Log.d("BleManager", "CMD 0x01 (serial): ${if (it != null) "ok" else "timeout"}") }

        // 4. CMD 0x04 — sync time
        sendAndAwait(buildSyncTime(), expectedCmd = 0x04)
            .also { Log.d("BleManager", "CMD 0x04 (time): ${if (it != null) "ok" else "timeout"}") }

        // 5. CMD 0x05 — get device status
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
            .also { Log.d("BleManager", "CMD 0x05 (status): ${if (it != null) "ok" else "timeout"}") }

        // 6. CMD 0x18 — unknown init command
        sendAndAwait(PKT_CMD18, expectedCmd = 0x18)
            .also { Log.d("BleManager", "CMD 0x18: ${if (it != null) "ok" else "timeout"}") }

        // 7. CMD 0x0A — list files
        collectFileList()

        // Only mark CONNECTED if the physical link is still up
        if (bluetoothGatt != null) {
            Log.i("BleManager", "runInitSequence: complete, marking CONNECTED")
            _bleState.update { it.copy(connectionState = ConnectionState.CONNECTED) }
            startPoller()
        } else {
            Log.w("BleManager", "runInitSequence: gatt is null after init — device disconnected mid-sequence")
        }
    }

    private fun startPoller() {
        stopPoller()
        pollJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15000L)
                refreshStatus()
            }
        }
    }

    private fun stopPoller() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Polls CMD 0x05 to sync the actual recording state from the device. */
    suspend fun refreshRecordingStatus() {
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Unknown service probe
    // ------------------------------------------------------------------

    suspend fun runServiceProbe() {
        val gatt = bluetoothGatt ?: run { Log.e("FW920_PROBE", "Not connected"); return }

        // The three unknown services and their write/notify UUIDs
        val targets = listOf(
            Triple("FFD0", "0000ffd1-0000-1000-8000-00805f9b34fb",
                             listOf("0000ffd2-0000-1000-8000-00805f9b34fb",
                                    "0000ffd3-0000-1000-8000-00805f9b34fb")),
            Triple("C0C0",  "0000c0c1-0000-1000-8000-00805f9b34fb",
                             listOf("0000c0c2-0000-1000-8000-00805f9b34fb")),
            Triple("E49A",  "e49a3002-f69a-11e8-8eb2-f2801f1b9fd1",
                             listOf("e49a3003-f69a-11e8-8eb2-f2801f1b9fd1"))
        )

        // Step 1 — subscribe to all notify chars in unknown services
        Log.i("FW920_PROBE", "=== SUBSCRIBING TO UNKNOWN SERVICE NOTIFICATIONS ===")
        targets.forEach { (name, _, notifyUuids) ->
            notifyUuids.forEach { notifyUuid ->
                val svcUuid = when (name) {
                    "FFD0" -> "0000ffd0-0000-1000-8000-00805f9b34fb"
                    "C0C0" -> "0000c0c0-0000-1000-8000-00805f9b34fb"
                    else   -> "e49a3001-f69a-11e8-8eb2-f2801f1b9fd1"
                }
                val svc = gatt.getService(UUID.fromString(svcUuid))
                val ch  = svc?.getCharacteristic(UUID.fromString(notifyUuid)) ?: return@forEach
                if (ch.properties and 0x10 != 0 || ch.properties and 0x20 != 0) {
                    enableNotification(gatt, ch)
                    withTimeoutOrNull(2000L) { descriptorChannel.receive() }
                    Log.i("FW920_PROBE", "  Subscribed to $name/$notifyUuid")
                }
            }
        }
        kotlinx.coroutines.delay(500)

        // Step 2 — for each unknown write char, try multiple payloads
        val payloads = listOf(
            "our CMD proto" to PKT_GET_STATUS,           // CMD 0x05 — known good packet
            "our CMD proto" to PKT_GET_FW_VERSION,       // CMD 0x02
            "our CMD proto" to PKT_GET_SERIAL,           // CMD 0x01
            "raw 0x00"      to byteArrayOf(0x00),
            "raw 0x01"      to byteArrayOf(0x01),
            "raw 0xFF"      to byteArrayOf(0xFF.toByte()),
            "raw AT"        to "AT\r\n".toByteArray(),   // ESP32 AT command firmware
            "raw AT+GMR"    to "AT+GMR\r\n".toByteArray(), // firmware version
            "raw AT+CWLAP"  to "AT+CWLAP\r\n".toByteArray(), // list Wi-Fi APs
        )

        targets.forEach { (name, writeUuid, _) ->
            val svcUuid = when (name) {
                "FFD0" -> "0000ffd0-0000-1000-8000-00805f9b34fb"
                "C0C0" -> "0000c0c0-0000-1000-8000-00805f9b34fb"
                else   -> "e49a3001-f69a-11e8-8eb2-f2801f1b9fd1"
            }
            val svc       = gatt.getService(UUID.fromString(svcUuid)) ?: run {
                Log.w("FW920_PROBE", "$name: service not found"); return@forEach
            }
            val writeChar = svc.getCharacteristic(UUID.fromString(writeUuid)) ?: run {
                Log.w("FW920_PROBE", "$name: write char not found"); return@forEach
            }

            Log.i("FW920_PROBE", "=== PROBING SERVICE $name (write=${writeUuid.take(8)}) ===")
            payloads.forEach { (label, data) ->
                Log.d("FW920_PROBE", "$name: sending [$label] ${data.size}b")

                // Write to the unknown characteristic directly (not via sendPacket)
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(writeChar, data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.value = data
                    @Suppress("DEPRECATION")
                    writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    if (gatt.writeCharacteristic(writeChar)) 0 else 1
                }

                // Drain the response channel for up to 800ms — log anything that arrives
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    val resp = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                        responseChannel.receive()
                    } ?: break
                    val hex = when (resp) {
                        is ParsedResponse.Unknown -> resp.payload.joinToString(" ") { "%02X".format(it) }
                        is ParsedResponse.AudioChunk -> resp.data.take(16).joinToString(" ") { "%02X".format(it) } + "..."
                        else -> resp.toString()
                    }
                    Log.i("FW920_PROBE", "  $name [$label] → $hex")
                }
                kotlinx.coroutines.delay(200)
            }
        }
        Log.i("FW920_PROBE", "=== SERVICE PROBE COMPLETE ===")
    }

    // ------------------------------------------------------------------
    // Diagnostic probe — triggered via ADB broadcast
    // ------------------------------------------------------------------

    suspend fun runProbe() {
        val gatt = bluetoothGatt ?: run {
            Log.e("FW920_PROBE", "Not connected — connect first")
            return
        }

        Log.i("FW920_PROBE", "=== GATT SERVICE INVENTORY ===")
        gatt.services.forEach { svc ->
            Log.i("FW920_PROBE", "SERVICE ${svc.uuid} (type=${svc.type})")
            svc.characteristics.forEach { ch ->
                val props = buildString {
                    if (ch.properties and 0x02 != 0) append("READ ")
                    if (ch.properties and 0x04 != 0) append("WRITE_NO_RSP ")
                    if (ch.properties and 0x08 != 0) append("WRITE ")
                    if (ch.properties and 0x10 != 0) append("NOTIFY ")
                    if (ch.properties and 0x20 != 0) append("INDICATE ")
                    if (ch.properties and 0x80 != 0) append("EXT_PROPS ")
                }
                Log.i("FW920_PROBE", "  CHAR ${ch.uuid}  [$props]")
                ch.descriptors.forEach { desc ->
                    Log.i("FW920_PROBE", "    DESC ${desc.uuid}")
                }
            }
        }

        Log.i("FW920_PROBE", "=== UNDOCUMENTED COMMAND PROBE (0x19–0x50) ===")
        for (cmd in 0x19..0x50) {
            sendPacket(buildPacket(cmd))
            val resp = withTimeoutOrNull(600L) { awaitResponse(cmd) }
            when {
                resp == null -> Log.d("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → timeout")
                resp is ParsedResponse.Unknown -> {
                    val hex = resp.payload.joinToString(" ") { "%02X".format(it) }
                    val str = resp.payload.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
                    Log.i("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → UNKNOWN payload=[$hex] str=\"$str\"")
                }
                else -> Log.i("FW920_PROBE", "CMD 0x${cmd.toString(16).uppercase()} → $resp")
            }
            kotlinx.coroutines.delay(150)
        }
        Log.i("FW920_PROBE", "=== PROBE COMPLETE ===")
    }

    // ------------------------------------------------------------------
    // Public suspend methods
    // ------------------------------------------------------------------

    suspend fun refreshStatus() {
        sendAndAwait(PKT_GET_STATUS, expectedCmd = 0x05)
    }

    suspend fun deleteFile(filename: String): Boolean {
        Log.i("BleManager", "deleteFile: '$filename'")
        // Two-phase delete: first 0x0D stages (payload=[0]), second 0x0D commits (payload=[1])
        val stage = sendAndAwait(buildDeleteFile(filename), expectedCmd = 0x0D)
        Log.i("BleManager", "deleteFile: stage=$stage")
        val commit = sendAndAwait(buildDeleteFile(filename), expectedCmd = 0x0D)
        Log.i("BleManager", "deleteFile: commit=$commit")
        val deleted = commit is ParsedResponse.Unknown &&
                commit.cmd == 0x0D &&
                commit.payload.firstOrNull()?.toInt() == 1
        if (!deleted) {
            Log.w("BleManager", "deleteFile: commit failed, payload=${
                (commit as? ParsedResponse.Unknown)?.payload?.toList()}")
            return false
        }
        collectFileList()
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        val stillPresent = _bleState.value.files.any { it.filename.equals(cleanName, ignoreCase = true) }
        Log.i("BleManager", "deleteFile: stillPresent=$stillPresent")
        return !stillPresent
    }

    suspend fun downloadFile(filename: String, onProgress: (Long) -> Unit): File? {
        val context = this.context
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        val nameBytes = cleanName.padEnd(14, ' ').take(14).toByteArray(Charsets.US_ASCII)

        val localDir = File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        val safeName = File(cleanName).name + ".mp3"
        val localFile = File(localDir, safeName).also { it.delete() }

        var totalBytes = 0L
        val fos = FileOutputStream(localFile)

        // Protocol: send CMD 0x0B → device responds Ack(0x0B) "ready" → streams AudioChunks →
        // signals Ack(0x0B) again when done. We treat the second Ack(0x0B) (after data) as EOF.
        try {
            val pkt = buildPacket(0x0B, nameBytes + byteArrayOf(0x00, 0x00, 0x00, 0x00))
            Log.i("BleManager", "downloadFile: CMD 0x0B '$cleanName'")
            sendPacket(pkt)

            var readyReceived = false
            val timeoutMs = 10000L
            var lastDataTime = System.currentTimeMillis()

            outer@ while (System.currentTimeMillis() - lastDataTime < timeoutMs) {
                val response = withTimeoutOrNull(2000) { responseChannel.receive() }
                if (response == null) {
                    Log.d("BleManager", "downloadFile: 2s idle, totalBytes=$totalBytes")
                    continue
                }

                when (response) {
                    is ParsedResponse.AudioChunk -> {
                        readyReceived = true
                        fos.write(response.data)
                        totalBytes += response.data.size
                        onProgress(totalBytes)
                        lastDataTime = System.currentTimeMillis()
                        if (totalBytes % (64 * 1024) < response.data.size) {
                            Log.d("BleManager", "downloadFile: $totalBytes bytes received")
                        }
                    }
                    is ParsedResponse.Ack -> {
                        Log.i("BleManager", "downloadFile: Ack cmd=0x${response.cmd.toString(16)} totalBytes=$totalBytes readyReceived=$readyReceived")
                        when (response.cmd) {
                            0x07 -> break@outer
                            0x0B -> {
                                if (!readyReceived) {
                                    // Initial "ready" Ack — keep waiting for data
                                    readyReceived = false  // stays false until first chunk
                                    lastDataTime = System.currentTimeMillis()
                                } else {
                                    // End-of-file Ack
                                    break@outer
                                }
                            }
                        }
                    }
                    else -> Log.d("BleManager", "downloadFile: unexpected=$response")
                }
            }
        } finally {
            fos.close()
        }

        Log.i("BleManager", "downloadFile: done '$cleanName', totalBytes=$totalBytes")
        return if (totalBytes > 0) localFile else null
    }

    // Add FileOutputStream import later or here if I can

    suspend fun listFiles() {
        collectFileList()
    }

    /** Probes CMD range 0x0D–0x17 with a filename payload to find the real delete command. */
    suspend fun probeDeleteCmds(filename: String) {
        val cleanName = if (filename.endsWith(".mp3")) filename.removeSuffix(".mp3") else filename
        val nameBytes = cleanName.padEnd(14, ' ').take(14).toByteArray(Charsets.US_ASCII)
        val skipKnown = setOf(0x0F)  // 0x0F is the periodic status update, skip it

        for (cmd in 0x0D..0x17) {
            if (cmd in skipKnown) continue
            Log.i("DeleteProbe", "Trying CMD 0x${cmd.toString(16).uppercase()} with filename '$cleanName'")
            val pkt = buildPacket(cmd, nameBytes)
            sendPacket(pkt)
            val resp = withTimeoutOrNull(1500L) { awaitResponse(cmd) }
            Log.i("DeleteProbe", "CMD 0x${cmd.toString(16).uppercase()} response: $resp")
            kotlinx.coroutines.delay(300)

            collectFileList()
            val gone = _bleState.value.files.none { it.filename.equals(cleanName, ignoreCase = true) }
            Log.i("DeleteProbe", "CMD 0x${cmd.toString(16).uppercase()} file gone=$gone")
            if (gone) {
                Log.i("DeleteProbe", "*** FOUND DELETE CMD: 0x${cmd.toString(16).uppercase()} ***")
                return
            }
        }
        Log.i("DeleteProbe", "No delete command found in 0x0D-0x17 range")
    }

    // ------------------------------------------------------------------
    // File list collector
    // ------------------------------------------------------------------

    private suspend fun collectFileList() {
        Log.i("BleManager", "collectFileList: sending PKT_LIST_FILES")
        sendPacket(PKT_LIST_FILES)
        val collected = mutableListOf<FileEntry>()
        val timeoutMs = 5000L
        val deadline  = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val response  = withTimeoutOrNull(remaining) {
                awaitResponse(expectedCmd = 0x0A)
            } ?: break

            when (response) {
                is ParsedResponse.FileList -> {
                    if (response.entry == null) {
                        Log.i("BleManager", "collectFileList: end-of-list, ${collected.size} files")
                        break
                    }
                    Log.i("BleManager", "collectFileList: entry=${response.entry.filename} ${response.entry.sizeBytes}B")
                    collected.add(response.entry)
                }
                else -> break
            }
        }

        Log.i("BleManager", "collectFileList: done, ${collected.size} files")
        _bleState.update { it.copy(files = collected) }
    }

    // ------------------------------------------------------------------
    // Low-level send helpers
    // ------------------------------------------------------------------

    private suspend fun sendPacket(data: ByteArray) {
        val char = writeChar ?: run {
            Log.w("BleManager", "sendPacket: writeChar is null — device disconnected?")
            return
        }
        val gatt = bluetoothGatt ?: run {
            Log.w("BleManager", "sendPacket: gatt is null — device disconnected?")
            return
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val cmd = if (data.size >= 4) data[3].toInt() and 0xFF else -1
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(char)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            }
            Log.d("BleManager", "sendPacket cmd=0x${cmd.toString(16)} result=$result")
            cont.resume(Unit)
        }
    }

    private suspend fun sendAndAwait(
        data: ByteArray,
        expectedCmd: Int,
        timeoutMs: Long = 3000L
    ): ParsedResponse? {
        sendPacket(data)
        return withTimeoutOrNull(timeoutMs) { awaitResponse(expectedCmd) }
    }

    /**
     * Drains [responseChannel] until a response matching [expectedCmd] is found.
     * Non-matching responses are discarded (they will already have been applied to state
     * by [handleIncoming]).
     */
    private suspend fun awaitResponse(expectedCmd: Int): ParsedResponse {
        for (response in responseChannel) {
            val matchesCmd = when (response) {
                is ParsedResponse.Serial          -> expectedCmd == 0x01
                is ParsedResponse.FwVersion       -> expectedCmd == 0x02
                is ParsedResponse.Ack             -> response.cmd == expectedCmd
                is ParsedResponse.Status          -> expectedCmd == 0x05 || expectedCmd == 0x0F
                is ParsedResponse.FileList        -> expectedCmd == 0x0A
                is ParsedResponse.RecordingStarted -> expectedCmd == 0x06
                is ParsedResponse.RecordingStopped -> expectedCmd == 0x08
                is ParsedResponse.AudioChunk      -> false
                is ParsedResponse.Unknown         -> response.cmd == expectedCmd
            }
            if (matchesCmd) return response
        }
        // Channel was closed — should not happen in normal operation
        error("responseChannel closed unexpectedly")
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    fun destroy() {
        disconnect()
        scope.cancel()
        responseChannel.close()
    }
}
