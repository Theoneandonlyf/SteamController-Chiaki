package com.metallic.chiaki.steam

import android.content.Context
import android.hardware.usb.*
import android.util.Log

/**
 * Steam Controller over USB: wired, or the wireless Puck dongle.
 * Reads state reports on the interrupt IN endpoint, keeps the controller out of "lizard mode"
 * (keyboard/mouse emulation) with a periodic keepalive, and optionally turns the raw IMU on.
 */
class SteamUsbTransport(
    context: Context,
    private val onReport: (ByteArray) -> Unit,
    private val onLost: () -> Unit
)
{
    companion object
    {
        private const val TAG = "SteamUsb"
        const val STEAM_VID = 0x28DE
        private const val TIMEOUT_MS = 1000
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var dataIface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val heldInterfaces = mutableListOf<UsbInterface>()

    @Volatile private var running = false
    @Volatile private var imuWanted = false
    private var featureId = SteamCommands.FEATURE_REPORT_CMD
    private var readThread: Thread? = null
    private var keepaliveThread: Thread? = null

    fun findDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == STEAM_VID }
    fun hasPermission(device: UsbDevice) = usb.hasPermission(device)
    val isOpen get() = running

    fun open(device: UsbDevice, imu: Boolean): Boolean
    {
        close()
        if(!usb.hasPermission(device))
            return false
        val conn = usb.openDevice(device) ?: return false

        // Prefer a vendor-defined (0xFF) interface, then HID, that has an IN endpoint.
        val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
            .sortedByDescending { when(it.interfaceClass) { 0xFF -> 2; UsbConstants.USB_CLASS_HID -> 1; else -> 0 } }
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        for(iface in candidates)
        {
            val ep = findEndpoint(iface, UsbConstants.USB_DIR_IN) ?: continue
            if(conn.claimInterface(iface, true))
            {
                chosen = iface; inEp = ep
                break
            }
        }
        if(chosen == null || inEp == null)
        {
            Log.e(TAG, "No usable interface")
            conn.close()
            return false
        }
        connection = conn
        dataIface = chosen
        epIn = inEp
        epOut = findEndpoint(chosen, UsbConstants.USB_DIR_OUT)

        // The Puck exposes extra interfaces (keyboard, mouse, ...). Claim them so the kernel does not
        // bind them, otherwise Android believes a hardware keyboard is attached and hides the soft keyboard.
        for(i in 0 until device.interfaceCount)
        {
            val iface = device.getInterface(i)
            if(iface.id != chosen.id && conn.claimInterface(iface, true))
                heldInterfaces.add(iface)
        }

        imuWanted = imu
        running = true
        disableLizard()
        if(imu) sendFeature(SteamCommands.imu(true, featureId))

        readThread = Thread({ readLoop(conn, inEp) }, "steam-usb-read").also { it.start() }
        keepaliveThread = Thread({ keepaliveLoop() }, "steam-usb-keepalive").also { it.start() }
        return true
    }

    fun close()
    {
        val wasRunning = running
        running = false
        keepaliveThread?.interrupt()
        try { readThread?.join(300) } catch(_: InterruptedException) {}
        try { keepaliveThread?.join(300) } catch(_: InterruptedException) {}
        readThread = null; keepaliveThread = null
        connection?.let { c ->
            try
            {
                if(wasRunning)
                {
                    // Hand the pad back to its firmware keyboard/mouse emulation.
                    sendFeature(SteamCommands.restoreDefaults(featureId))
                    sendFeature(SteamCommands.loadDefaultSettings(featureId))
                }
                heldInterfaces.forEach { c.releaseInterface(it) }
                dataIface?.let { c.releaseInterface(it) }
                c.close()
            }
            catch(e: Exception) { Log.w(TAG, "close: ${e.message}") }
        }
        heldInterfaces.clear()
        connection = null; dataIface = null; epIn = null; epOut = null
    }

    fun setImu(enabled: Boolean)
    {
        imuWanted = enabled
        if(running) sendFeature(SteamCommands.imu(enabled, featureId))
    }

    /** Magnitudes 0..65535. (0, 0) stops. */
    fun rumble(left: Int, right: Int)
    {
        val c = connection ?: return
        val buf = SteamCommands.usbRumble(left, right)
        val out = epOut
        if(out != null)
            c.bulkTransfer(out, buf, buf.size, TIMEOUT_MS)
        else
            c.controlTransfer(0x21, 0x09, (0x02 shl 8) or (buf[0].toInt() and 0xFF), dataIface?.id ?: 0, buf, buf.size, TIMEOUT_MS)
    }

    private fun readLoop(c: UsbDeviceConnection, ep: UsbEndpoint)
    {
        val buf = ByteArray(64)
        var consecutiveFailures = 0
        while(running)
        {
            val len = c.bulkTransfer(ep, buf, buf.size, 100)
            if(len > 0)
            {
                consecutiveFailures = 0
                onReport(buf.copyOf(len))
            }
            else
            {
                // -1 is a normal timeout while idle; a long unbroken run of them means the device is gone.
                if(++consecutiveFailures > 100 && usb.deviceList.values.none { it.vendorId == STEAM_VID })
                {
                    running = false
                    onLost()
                    return
                }
                if(len < 0) Thread.sleep(2)
            }
        }
    }

    private fun keepaliveLoop()
    {
        while(running)
        {
            try { Thread.sleep(SteamCommands.KEEPALIVE_MS) } catch(_: InterruptedException) { return }
            if(!running) return
            disableLizard()
            if(imuWanted) sendFeature(SteamCommands.imu(true, featureId))
        }
    }

    private fun disableLizard()
    {
        if(!sendFeature(SteamCommands.clearDigitalMappings(featureId)))
        {
            // Some firmware revisions only answer on feature report 0x02.
            if(featureId == SteamCommands.FEATURE_REPORT_CMD)
            {
                featureId = SteamCommands.FEATURE_REPORT_CMD2
                sendFeature(SteamCommands.clearDigitalMappings(featureId))
            }
        }
        sendFeature(SteamCommands.padsRaw(featureId))
    }

    private fun sendFeature(buf: ByteArray): Boolean
    {
        val c = connection ?: return false
        val wValue = (0x03 shl 8) or (buf[0].toInt() and 0xFF)
        val idx = dataIface?.id ?: 0
        var r = c.controlTransfer(0x21, 0x09, wValue, idx, buf, buf.size, TIMEOUT_MS)
        if(r < 0 && idx != 0)
            r = c.controlTransfer(0x21, 0x09, wValue, 0, buf, buf.size, TIMEOUT_MS)
        return r >= 0
    }

    private fun findEndpoint(iface: UsbInterface, dir: Int): UsbEndpoint?
    {
        for(i in 0 until iface.endpointCount)
        {
            val ep = iface.getEndpoint(i)
            if(ep.direction == dir && (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT || ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK))
                return ep
        }
        return null
    }
}
