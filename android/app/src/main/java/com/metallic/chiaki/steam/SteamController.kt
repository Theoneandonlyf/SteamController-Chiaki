package com.metallic.chiaki.steam

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.metallic.chiaki.lib.ControllerState

/**
 * Process-wide owner of the Steam Controller connection.
 *
 * The controller is read here (USB or Bluetooth LE), mapped to a DualSense-style [ControllerState]
 * by [SteamMapper], and handed straight to the stream via [onControllerState] — no Shizuku,
 * no virtual Android gamepad in between.
 */
object SteamController
{
    private const val ACTION_USB_PERMISSION = "com.metallic.chiaki.steam.USB_PERMISSION"

    enum class Status { DISCONNECTED, WAITING_USB_PERMISSION, CONNECTING, CONNECTED }

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private var usb: SteamUsbTransport? = null
    private var ble: SteamBleTransport? = null
    private var receiversRegistered = false
    private var activeTransport: TransportChoice = TransportChoice.USB

    @Volatile var status = Status.DISCONNECTED; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var battery: SteamBattery? = null; private set
    @Volatile var lastState: SteamState? = null; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var imuSeen = false; private set
    /** Largest raw pad coordinate seen while touching, for the Debug screen's range helper. */
    @Volatile var padMaxX = 0; private set
    @Volatile var padMaxY = 0; private set

    fun resetPadStats() { padMaxX = 0; padMaxY = 0 }

    /** Receives every mapped frame (called from the transport thread). */
    @Volatile var onControllerState: ((ControllerState) -> Unit)? = null
    /** Mapped actions (mic mute, gyro toggle, ...). Called on the main thread. */
    @Volatile var onAction: ((PsTarget) -> Unit)? = null
    /** Connection state changed. Called on the main thread. */
    @Volatile var onStatusChanged: (() -> Unit)? = null

    private var store: SteamStore? = null
    @Volatile private var profileCache: SteamProfile = SteamProfile("Default")

    val mapper = SteamMapper({ profileCache }, { a -> main.post { onAction?.invoke(a) } }) { left ->
        val k = profileCache.rumbleIntensity / 100f
        if (k > 0f)
        {
            val mag = (45000 * k).toInt().coerceIn(1000, 65535)
            when (activeTransport)
            {
                TransportChoice.USB -> {
                    if (left) usb?.rumble(mag, 0) else usb?.rumble(0, mag)
                    main.postDelayed({
                        val (curL, curR) = if (lastRumbleL > 0 || lastRumbleR > 0) lastRumbleL to lastRumbleR else 0 to 0
                        usb?.rumble(curL, curR)
                    }, 25)
                }
                TransportChoice.BLUETOOTH -> {
                    ble?.rumble(if (left) mag else 0, if (!left) mag else 0)
                    main.postDelayed({ ble?.stopHaptics() }, 25)
                }
            }
        }
    }

    private var lastSent: ControllerState? = null
    private var lastRumbleL = -1
    private var lastRumbleR = -1

    private var streams = 0
    /** True when a stream (not the settings screen) opened the connection, so it should close it again. */
    @Volatile var autoStop = false

    fun streamOpened() { streams++ }

    fun streamClosed()
    {
        streams--
        if(streams <= 0 && autoStop)
            main.postDelayed({
                // Delay so a restarted stream (e.g. toggling video) keeps the connection.
                if(streams <= 0 && autoStop) { autoStop = false; stop() }
            }, 4000)
    }

    val isConnected get() = status == Status.CONNECTED
    val currentProfile get() = profileCache

    fun init(context: Context)
    {
        if(app != null) return
        app = context.applicationContext
        store = SteamStore(context)
        reloadProfile()
    }

    /** Re-read the active profile from storage. Call after the user edits or switches profiles. */
    fun reloadProfile()
    {
        val s = store ?: return
        profileCache = s.activeProfile()
    }

    private fun setStatus(s: Status, error: String? = null)
    {
        status = s
        lastError = error
        main.post { onStatusChanged?.invoke() }
    }

    /**
     * Connect using the transport saved in settings. For Bluetooth the caller must already hold
     * BLUETOOTH_CONNECT on Android 12+. Safe to call when already connected (no-op).
     */
    fun start(): Status
    {
        val ctx = app ?: return Status.DISCONNECTED
        val st = store ?: return Status.DISCONNECTED
        if(status == Status.CONNECTED || status == Status.CONNECTING) return status
        reloadProfile()
        registerReceivers(ctx)
        mapper.reset()
        lastSent = null; lastRumbleL = -1; lastRumbleR = -1
        battery = null; lastState = null; frameCount = 0; imuSeen = false
        activeTransport = st.transport

        return when(activeTransport)
        {
            TransportChoice.USB -> startUsb(ctx)
            TransportChoice.BLUETOOTH -> startBle(ctx, st)
        }
    }

    private fun startUsb(ctx: Context): Status
    {
        val t = usb ?: SteamUsbTransport(ctx, ::onRaw, ::onUsbLost).also { usb = it }
        val dev = t.findDevice()
        if(dev == null)
        {
            setStatus(Status.DISCONNECTED, "No Steam Controller found on USB. Plug it (or the Puck) into the phone's USB port.")
            return status
        }
        if(!t.hasPermission(dev))
        {
            setStatus(Status.WAITING_USB_PERMISSION)
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags)
            um.requestPermission(dev, pi)
            return status
        }
        return openUsb(dev)
    }

    private fun openUsb(dev: UsbDevice): Status
    {
        val t = usb ?: return status
        setStatus(Status.CONNECTING)
        val ok = t.open(dev, imu = true)
        if(ok) setStatus(Status.CONNECTED)
        else setStatus(Status.DISCONNECTED, "Could not open the USB controller.")
        return status
    }

    private fun startBle(ctx: Context, st: SteamStore): Status
    {
        val t = ble ?: SteamBleTransport(ctx).also { ble = it }
        if(!t.isAvailable) { setStatus(Status.DISCONNECTED, "Bluetooth is off."); return status }
        val address = st.bluetoothAddress
        if(address == null) { setStatus(Status.DISCONNECTED, "Pick a paired Steam Controller first."); return status }
        val device = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(address) }
                     catch(e: Exception) { null }
        if(device == null) { setStatus(Status.DISCONNECTED, "Saved Bluetooth device is invalid."); return status }

        setStatus(Status.CONNECTING)
        t.connect(device, ::onRaw) { connected ->
            if(connected)
            {
                setStatus(Status.CONNECTED)
                if(st.bleGyroExperiment) main.postDelayed({ ble?.tryEnableImuRaw() }, 1500)
            }
            else if(status != Status.DISCONNECTED)
                setStatus(Status.DISCONNECTED, "Bluetooth controller disconnected.")
        }
        return status
    }

    fun stop()
    {
        try { usb?.close() } catch(_: Exception) {}
        try { ble?.stopHaptics(); ble?.disconnect() } catch(_: Exception) {}
        mapper.reset()
        lastSent = null
        val wasDisconnected = status == Status.DISCONNECTED
        status = Status.DISCONNECTED
        if(!wasDisconnected) main.post { onStatusChanged?.invoke() }
    }

    private fun onUsbLost()
    {
        status = Status.DISCONNECTED
        lastError = "USB controller unplugged."
        main.post { onStatusChanged?.invoke() }
    }

    private fun onRaw(raw: ByteArray)
    {
        if(raw.isEmpty()) return
        SteamParser.parseBattery(raw)?.let { battery = it; return }
        val s = SteamParser.parseState(raw) ?: return
        lastState = s
        frameCount++
        if(s.imuValid) imuSeen = true
        if(s.has(SteamBtn.TP_LT) || s.leftPadContact > 0)
        {
            padMaxX = maxOf(padMaxX, kotlin.math.abs(s.leftPadX)); padMaxY = maxOf(padMaxY, kotlin.math.abs(s.leftPadY))
        }
        if(s.has(SteamBtn.TP_RT) || s.rightPadContact > 0)
        {
            padMaxX = maxOf(padMaxX, kotlin.math.abs(s.rightPadX)); padMaxY = maxOf(padMaxY, kotlin.math.abs(s.rightPadY))
        }
        val cs = mapper.map(s)
        if(cs == lastSent) return
        lastSent = cs
        onControllerState?.invoke(cs)
    }

    /** Rumble from the PS5 (0..255 per motor), scaled by the profile's intensity. */
    fun rumble(left: Int, right: Int)
    {
        if(left == lastRumbleL && right == lastRumbleR) return
        lastRumbleL = left; lastRumbleR = right
        val k = profileCache.rumbleIntensity / 100f
        val l = (left * 257 * k).toInt().coerceIn(0, 65535)
        val r = (right * 257 * k).toInt().coerceIn(0, 65535)
        when(activeTransport)
        {
            TransportChoice.USB -> usb?.rumble(l, r)
            TransportChoice.BLUETOOTH -> ble?.rumble(l, r)
        }
    }

    fun testRumble()
    {
        rumble(255, 255)
        main.postDelayed({ rumble(0, 0) }, 600)
    }

    fun pairedBluetoothControllers() = (ble ?: app?.let { SteamBleTransport(it).also { t -> ble = t } })?.pairedControllers().orEmpty()

    private fun registerReceivers(ctx: Context)
    {
        if(receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver()
        {
            override fun onReceive(c: Context, intent: Intent)
            {
                when(intent.action)
                {
                    ACTION_USB_PERMISSION ->
                    {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if(granted)
                        {
                            status = Status.DISCONNECTED
                            start()
                        }
                        else setStatus(Status.DISCONNECTED, "USB permission was denied.")
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    {
                        val d: UsbDevice? =
                            if(Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if(d?.vendorId == SteamUsbTransport.STEAM_VID && activeTransport == TransportChoice.USB)
                        {
                            try { usb?.close() } catch(_: Exception) {}
                            onUsbLost()
                        }
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
