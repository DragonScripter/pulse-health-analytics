package com.jma.pulsehealthanalytics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID


/**
 * Connects to the watch by MAC address, subscribes to its vendor characteristics
 * and decodes raw packets into plain integers. No GloryFit / Health Connect.
 *
 * Caller must hold BLUETOOTH_CONNECT (API 31+) before calling [connect].
 */
@SuppressLint("MissingPermission")
class WatchBleManager(
    private val context: Context,
    private val macAddress: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onHeartRate(bpm: Int)
        fun onSpo2(percent: Int)
        fun onActivity(steps: Int, distanceMetres: Int, caloriesKcal: Int)
        fun onConnectionChanged(connected: Boolean) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()

    @Volatile
    private var shuttingDown = false

    fun connect() {
        shuttingDown = false
        val adapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled")
            return
        }
        val device = adapter.getRemoteDevice(macAddress)
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice_TRANSPORT_LE)
    }

    /** Safely tears down the GATT connection and releases all resources. */
    fun shutdown() {
        shuttingDown = true
        mainHandler.removeCallbacksAndMessages(null)
        pendingSubscriptions.clear()
        gatt?.let {
            try {
                it.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
            }
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        listener.onConnectionChanged(true)
                        g.discoverServices()
                    } else {
                        g.close()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onConnectionChanged(false)
                    pendingSubscriptions.clear()
                    g.close()
                    if (gatt === g) gatt = null
                    if (!shuttingDown) {
                        mainHandler.postDelayed({ if (!shuttingDown) connect() }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                return
            }
            pendingSubscriptions.clear()
            g.getService(SERVICE_VITALS)?.getCharacteristic(CHAR_VITALS)
                ?.let { pendingSubscriptions.add(it) }
                ?: Log.w(TAG, "Vitals characteristic 0x33F2 not found")
            g.getService(SERVICE_ACTIVITY)?.getCharacteristic(CHAR_ACTIVITY)
                ?.let { pendingSubscriptions.add(it) }
                ?: Log.w(TAG, "Activity characteristic 0xFEA1 not found")
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // CCCD writes must be serialised: next one only after this completes.
            subscribeNext(g)
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handlePacket(characteristic.uuid, value)
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handlePacket(characteristic.uuid, characteristic.value ?: return)
            }
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        val ch = pendingSubscriptions.poll() ?: return
        if (!g.setCharacteristicNotification(ch, true)) {
            subscribeNext(g)
            return
        }
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            subscribeNext(g)
            return
        }
        val value =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        if (!started) subscribeNext(g)
    }

    // ---- Packet decoding ----------------------------------------------------

    private fun handlePacket(uuid: UUID, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        Log.d(TAG, "RX $uuid ${bytes.joinToString(" ") { "%02X".format(it) }}")
        when (uuid) {
            CHAR_VITALS -> decodeVitals(bytes)
            CHAR_ACTIVITY -> decodeActivity(bytes)
        }
    }

    /** First byte: 0xE5 = heart rate, 0x34 = SpO2. Last byte = value. */
    private fun decodeVitals(b: ByteArray) {
        val value = b.last().toInt() and 0xFF
        when (b.first().toInt() and 0xFF) {
            0xE5 -> if (value > 0) listener.onHeartRate(value)
            0x34 -> if (value > 0) listener.onSpo2(value)
        }
    }

    /**
     * Little-endian 3-byte integers. Offsets below are an ASSUMPTION
     * (steps, distance, calories back to back from byte 0) — verify against
     * the logged hex and adjust the constants if there is a header byte.
     */
    private fun decodeActivity(b: ByteArray) {
        if (b.size < OFFSET_CALORIES + 3) return
        listener.onActivity(
            steps = le24(b, OFFSET_STEPS),
            distanceMetres = le24(b, OFFSET_DISTANCE),
            caloriesKcal = le24(b, OFFSET_CALORIES),
        )
    }

    private fun le24(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16)

    companion object {
        private const val TAG = "WatchBleManager"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val BluetoothDevice_TRANSPORT_LE = 2 // BluetoothDevice.TRANSPORT_LE

        private const val OFFSET_STEPS = 1
        private const val OFFSET_DISTANCE = 4
        private const val OFFSET_CALORIES = 7

        private fun uuid16(short: String) =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        val SERVICE_VITALS: UUID = uuid16("55ff")
        val CHAR_VITALS: UUID = uuid16("33f2")
        val SERVICE_ACTIVITY: UUID = uuid16("fee7")
        val CHAR_ACTIVITY: UUID = uuid16("fea1")
        val CCCD: UUID = uuid16("2902")
    }
}