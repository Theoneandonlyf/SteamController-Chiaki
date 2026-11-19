package com.metallic.chiaki.steam

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steam Controller over Bluetooth LE, using Valve's vendor GATT service
 * (the standard HID service is claimed by Android itself).
 *
 * GATT allows one outstanding operation, so every write goes through a small bounded queue.
 * Ported from SteamController-Android's BluetoothHidManager, which was validated on hardware.
 */
@SuppressLint("MissingPermission")
class SteamBleTransport(private val context: Context)
{
    companion object
    {
        private const val TAG = "SteamBle"
        val VALVE_SERVICE: UUID = UUID.fromString("100f6c32-1735-4313-b402-38567131e5f3")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NOTIFY_LOW = 0x100f6c75L
        private const val NOTIFY_HIGH = 0x100f6c7aL
        private const val WRITE_LOW = 0x100f6cb5L
        private const val WRITE_HIGH = 0x100f6cbeL
        private const val BATTERY_CHAR = 0x100f6c78L
        private const val BATTERY_LEN = 14
        private const val MAX_QUEUED = 8
        val NAME_HINTS = listOf("Steam Ctrl", "Steam Controller", "SteamController", "Valve")
    }

    private enum class State { IDLE, CONNECTING, MTU, DISCOVERING, SUBSCRIBING, READY }

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val subs = mutableListOf<BluetoothGattCharacteristic>()
    private var subIndex = 0
    @Volatile private var state = State.IDLE
    private val queue = ArrayDeque<Pair<ByteArray, Int>>()
    private val inFlight = AtomicBoolean(false)
    private var onReport: ((ByteArray) -> Unit)? = null
    private var onConnection: ((Boolean) -> Unit)? = null

    val isAvailable get() = adapter?.isEnabled == true
    val isReady get() = state == State.READY

    fun pairedControllers(): List<BluetoothDevice>
    {
        val a = adapter ?: return emptyList()
        return try
        {
            a.bondedDevices.orEmpty().filter { d ->
                val n = d.name ?: return@filter false
                NAME_HINTS.any { n.contains(it, ignoreCase = true) }
            }
        }
        catch(e: SecurityException) { emptyList() }
    }

    fun connect(device: BluetoothDevice, onReport: (ByteArray) -> Unit, onConnection: (Boolean) -> Unit)
    {
        this.onReport = onReport
        this.onConnection = onConnection
        state = State.CONNECTING
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect()
    {
        try { gatt?.disconnect(); gatt?.close() } catch(_: Throwable) {}
        reset()
    }

    private fun reset()
    {
        gatt = null; writeChar = null; subs.clear(); subIndex = 0; state = State.IDLE
        synchronized(queue) { queue.clear() }
        inFlight.set(false)
    }

    private fun enqueue(payload: ByteArray, type: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    {
        if(state != State.READY) return
        synchronized(queue)
        {
            while(queue.size >= MAX_QUEUED) queue.removeFirst()
            queue.addLast(payload to type)
        }
        pump()
    }

    private fun pump()
    {
        val g = gatt ?: return
        val ch = writeChar ?: return
        if(!inFlight.compareAndSet(false, true)) return
        val next = synchronized(queue) { queue.removeFirstOrNull() }
        if(next == null) { inFlight.set(false); return }
        try
        {
            ch.value = next.first
            ch.writeType = next.second
            if(!g.writeCharacteristic(ch)) { inFlight.set(false); pump() }
        }
        catch(t: Throwable) { inFlight.set(false) }
    }

    /** Magnitudes 0..65535. The BLE vendor channel only exposes a pulse train, so this is duty-cycle based. */
    fun rumble(left: Int, right: Int)
    {
        enqueue(SteamCommands.blePulse(0, left), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        enqueue(SteamCommands.blePulse(1, right), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    fun stopHaptics() = rumble(0, 0)

    /**
     * EXPERIMENTAL. The vendor characteristic is a raw command channel ([cmd, args...], no report id,
     * no padding); the only confirmed command there is the haptic pulse. Raw IMU may or may not be
     * switchable this way — the Debug screen shows whether IMU data actually arrives.
     */
    fun tryEnableImuRaw()
    {
        enqueue(byteArrayOf(0x87.toByte(), 0x03, 0x30, 0x18, 0x00))
    }

    private val callback = object : BluetoothGattCallback()
    {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int)
        {
            when(newState)
            {
                BluetoothProfile.STATE_CONNECTED ->
                {
                    onConnection?.invoke(true)
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) // ~11 ms interval
                    if(state == State.CONNECTING)
                    {
                        state = State.MTU
                        if(!g.requestMtu(100)) { state = State.DISCOVERING; g.discoverServices() }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                {
                    onConnection?.invoke(false)
                    try { g.close() } catch(_: Throwable) {}
                    reset()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int)
        {
            if(state != State.MTU) return
            state = State.DISCOVERING
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int)
        {
            if(state != State.DISCOVERING) return
            val valve = if(status == BluetoothGatt.GATT_SUCCESS) g.getService(VALVE_SERVICE) else null
            if(valve == null) { Log.e(TAG, "Valve vendor service not found"); return }
            subs.clear(); writeChar = null
            for(ch in valve.characteristics)
            {
                val short = shortUuid(ch.uuid) ?: continue
                val notify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val write = (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                if(notify && short in NOTIFY_LOW..NOTIFY_HIGH) subs.add(ch)
                if(write && short in WRITE_LOW..WRITE_HIGH && writeChar == null) writeChar = ch
            }
            subIndex = 0
            state = State.SUBSCRIBING
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int)
        {
            if(state == State.SUBSCRIBING) subscribeNext(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int)
        {
            inFlight.set(false)
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic)
        {
            val data = ch.value ?: return
            val short = shortUuid(ch.uuid)
            // BLE strips the HID report id; put it back so USB and BLE share one parser.
            val out: ByteArray = when
            {
                data.size >= 40 -> ByteArray(data.size + 1).also {
                    it[0] = SteamParser.REPORT_STATE.toByte()
                    System.arraycopy(data, 0, it, 1, data.size)
                }
                short == BATTERY_CHAR && data.size == BATTERY_LEN ->
                    byteArrayOf(SteamParser.REPORT_BATTERY.toByte(), data[0], data[1])
                else -> return
            }
            onReport?.invoke(out)
        }
    }

    private fun subscribeNext(g: BluetoothGatt)
    {
        if(subIndex >= subs.size)
        {
            state = State.READY
            stopHaptics() // clear any pulse train left over from a previous session
            return
        }
        val ch = subs[subIndex++]
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD)
        if(cccd == null) { subscribeNext(g); return }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if(!g.writeDescriptor(cccd)) subscribeNext(g)
    }

    private fun shortUuid(uuid: UUID): Long?
    {
        val s = uuid.toString()
        if(!s.endsWith("-1735-4313-b402-38567131e5f3")) return null
        return s.substring(0, 8).toLongOrNull(16)
    }
}
