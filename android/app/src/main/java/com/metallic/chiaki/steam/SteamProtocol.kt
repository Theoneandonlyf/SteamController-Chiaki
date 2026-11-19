// Steam Controller (2026 / "Ibex") protocol: state model, report parser and command builders.
//
// Byte layouts and command ids are taken from:
//   - ddeverill/SteamlessController  src/steam/SteamController.h  (MIT)
//   - SonicDX12/SteamController-Android (MIT), which validated them on hardware
// Part of the merged Steam Controller -> PS5 bridge. Licensed with the rest of the app.

package com.metallic.chiaki.steam

/** Source buttons/flags, packed as byte2 | byte3<<8 | byte4<<16 | byte5<<24. */
object SteamBtn
{
    const val A            = 0x00000001
    const val B            = 0x00000002
    const val X            = 0x00000004
    const val Y            = 0x00000008
    const val QUICK_ACCESS = 0x00000010
    const val RS           = 0x00000020 // right stick click
    const val MENU         = 0x00000040
    const val R4           = 0x00000080
    const val R5           = 0x00000100
    const val RB           = 0x00000200
    const val DPAD_DOWN    = 0x00000400
    const val DPAD_RIGHT   = 0x00000800
    const val DPAD_LEFT    = 0x00001000
    const val DPAD_UP      = 0x00002000
    const val VIEW         = 0x00004000
    const val LS           = 0x00008000 // left stick click
    const val STEAM        = 0x00010000 // Steam / guide
    const val L4           = 0x00020000
    const val L5           = 0x00040000
    const val LB           = 0x00080000
    const val RS_TOUCH     = 0x00100000
    const val TP_RT        = 0x00200000 // right pad touched (or clicked)
    const val TP_RT_CLICK  = 0x00400000 // right pad physically pressed
    const val RT_FULL      = 0x00800000
    const val LS_TOUCH     = 0x01000000
    const val TP_LT        = 0x02000000
    const val TP_LT_CLICK  = 0x04000000
    const val LT_FULL      = 0x08000000
    const val GRIP_RT      = 0x10000000
    const val GRIP_LT      = 0x20000000

    /** Mechanical switches only. Capacitive flags (touch / grip) are too noisy to debounce. */
    const val DEBOUNCED = A or B or X or Y or QUICK_ACCESS or RS or MENU or R4 or R5 or RB or
        DPAD_DOWN or DPAD_RIGHT or DPAD_LEFT or DPAD_UP or VIEW or LS or STEAM or L4 or L5 or LB or
        RT_FULL or LT_FULL
}

/** A source input the user can map. [mask] is a SteamBtn flag. */
enum class SteamSource(val label: String, val mask: Int, val group: String)
{
    A("A", SteamBtn.A, "Face"),
    B("B", SteamBtn.B, "Face"),
    X("X", SteamBtn.X, "Face"),
    Y("Y", SteamBtn.Y, "Face"),
    LB("Left bumper (LB)", SteamBtn.LB, "Bumpers / triggers"),
    RB("Right bumper (RB)", SteamBtn.RB, "Bumpers / triggers"),
    LT_FULL("Left trigger, full pull", SteamBtn.LT_FULL, "Bumpers / triggers"),
    RT_FULL("Right trigger, full pull", SteamBtn.RT_FULL, "Bumpers / triggers"),
    DPAD_UP("D-pad up", SteamBtn.DPAD_UP, "D-pad"),
    DPAD_DOWN("D-pad down", SteamBtn.DPAD_DOWN, "D-pad"),
    DPAD_LEFT("D-pad left", SteamBtn.DPAD_LEFT, "D-pad"),
    DPAD_RIGHT("D-pad right", SteamBtn.DPAD_RIGHT, "D-pad"),
    LS("Left stick click", SteamBtn.LS, "Sticks"),
    RS("Right stick click", SteamBtn.RS, "Sticks"),
    MENU("Menu (≡)", SteamBtn.MENU, "System"),
    VIEW("View (⧉)", SteamBtn.VIEW, "System"),
    STEAM("Steam / Guide", SteamBtn.STEAM, "System"),
    QUICK_ACCESS("Quick access (…)", SteamBtn.QUICK_ACCESS, "System"),
    L4("Back paddle L4", SteamBtn.L4, "Back paddles / grips"),
    L5("Back paddle L5", SteamBtn.L5, "Back paddles / grips"),
    R4("Back paddle R4", SteamBtn.R4, "Back paddles / grips"),
    R5("Back paddle R5", SteamBtn.R5, "Back paddles / grips"),
    GRIP_LT("Left grip squeeze", SteamBtn.GRIP_LT, "Back paddles / grips"),
    GRIP_RT("Right grip squeeze", SteamBtn.GRIP_RT, "Back paddles / grips"),
    TP_LT_CLICK("Left pad click", SteamBtn.TP_LT_CLICK, "Trackpads"),
    TP_RT_CLICK("Right pad click", SteamBtn.TP_RT_CLICK, "Trackpads"),
    TP_LT_TOUCH("Left pad touch", SteamBtn.TP_LT, "Trackpads"),
    TP_RT_TOUCH("Right pad touch", SteamBtn.TP_RT, "Trackpads");

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } }
}

data class SteamState(
    val buttons: Int = 0,
    val leftTrigger: Int = 0,   // 0..32767
    val rightTrigger: Int = 0,
    val leftX: Int = 0, val leftY: Int = 0,     // -32768..32767, +Y = up
    val rightX: Int = 0, val rightY: Int = 0,
    val leftPadX: Int = 0, val leftPadY: Int = 0, val leftPadContact: Int = 0,
    val rightPadX: Int = 0, val rightPadY: Int = 0, val rightPadContact: Int = 0,
    val imuValid: Boolean = false,
    val accelX: Int = 0, val accelY: Int = 0, val accelZ: Int = 0,
    val gyroX: Int = 0, val gyroY: Int = 0, val gyroZ: Int = 0
)
{
    fun has(mask: Int) = (buttons and mask) != 0
}

data class SteamBattery(val chargeState: Int, val percent: Int)

object SteamParser
{
    const val REPORT_STATE = 0x45
    const val REPORT_STATE_LEGACY = 0x42
    const val REPORT_BATTERY = 0x43
    private const val MIN_LEN = 40
    private const val IMU_LEN = 46

    fun isState(id: Int) = id == REPORT_STATE || id == REPORT_STATE_LEGACY

    /** [report] must start with the report id (USB does; BLE has it re-attached by the transport). */
    fun parseState(r: ByteArray): SteamState?
    {
        if(r.size < MIN_LEN || !isState(r[0].toInt() and 0xFF))
            return null
        val imu = r.size >= IMU_LEN
        return SteamState(
            buttons = (r[2].toInt() and 0xFF) or ((r[3].toInt() and 0xFF) shl 8) or
                ((r[4].toInt() and 0xFF) shl 16) or ((r[5].toInt() and 0xFF) shl 24),
            leftTrigger = u16(r, 6), rightTrigger = u16(r, 8),
            leftX = s16(r, 10), leftY = s16(r, 12), rightX = s16(r, 14), rightY = s16(r, 16),
            leftPadX = s16(r, 18), leftPadY = s16(r, 20), leftPadContact = u16(r, 22),
            rightPadX = s16(r, 24), rightPadY = s16(r, 26), rightPadContact = u16(r, 28),
            imuValid = imu,
            accelX = if(imu) s16(r, 34) else 0, accelY = if(imu) s16(r, 36) else 0, accelZ = if(imu) s16(r, 38) else 0,
            gyroX = if(imu) s16(r, 40) else 0, gyroY = if(imu) s16(r, 42) else 0, gyroZ = if(imu) s16(r, 44) else 0
        )
    }

    /** Battery report 0x43: [id, chargeState, percent]. */
    fun parseBattery(r: ByteArray): SteamBattery?
    {
        if(r.size < 3 || (r[0].toInt() and 0xFF) != REPORT_BATTERY)
            return null
        return SteamBattery(r[1].toInt() and 0xFF, (r[2].toInt() and 0xFF).coerceIn(0, 100))
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
}

object SteamCommands
{
    const val FEATURE_REPORT_CMD = 0x01
    const val FEATURE_REPORT_CMD2 = 0x02
    private const val CMD_CLEAR_DIGITAL_MAPPINGS = 0x81 // lizard mode off
    private const val CMD_SET_DEFAULT_MAPPINGS = 0x85   // lizard mode on
    private const val CMD_SET_SETTINGS = 0x87
    private const val CMD_LOAD_DEFAULT_SETTINGS = 0x8E
    private const val SETTING_RIGHT_TRACKPAD_MODE = 0x07
    private const val SETTING_LEFT_TRACKPAD_MODE = 0x08
    private const val SETTING_IMU_MODE = 0x30
    private const val TRACKPAD_NONE = 0x0000
    private const val IMU_MODE_OFF = 0x0000
    private const val IMU_MODE_RAW_ACCEL_GYRO = 0x0018
    const val OUT_HAPTIC_RUMBLE = 0x80
    const val FEATURE_SIZE = 64
    const val KEEPALIVE_MS = 2000L

    private fun build(cmd: Int, payload: ByteArray, id: Int): ByteArray
    {
        val buf = ByteArray(FEATURE_SIZE)
        buf[0] = id.toByte(); buf[1] = cmd.toByte(); buf[2] = payload.size.toByte()
        payload.copyInto(buf, 3)
        return buf
    }

    private fun settings(vararg kv: Pair<Int, Int>): ByteArray
    {
        val b = ByteArray(kv.size * 3)
        kv.forEachIndexed { i, (k, v) ->
            b[i * 3] = k.toByte(); b[i * 3 + 1] = (v and 0xFF).toByte(); b[i * 3 + 2] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    fun clearDigitalMappings(id: Int) = build(CMD_CLEAR_DIGITAL_MAPPINGS, ByteArray(0), id)
    fun padsRaw(id: Int) = build(CMD_SET_SETTINGS, settings(SETTING_RIGHT_TRACKPAD_MODE to TRACKPAD_NONE, SETTING_LEFT_TRACKPAD_MODE to TRACKPAD_NONE), id)
    fun imu(enabled: Boolean, id: Int) = build(CMD_SET_SETTINGS, settings(SETTING_IMU_MODE to if(enabled) IMU_MODE_RAW_ACCEL_GYRO else IMU_MODE_OFF), id)
    fun restoreDefaults(id: Int) = build(CMD_SET_DEFAULT_MAPPINGS, ByteArray(0), id)
    fun loadDefaultSettings(id: Int) = build(CMD_LOAD_DEFAULT_SETTINGS, ByteArray(0), id)

    /** USB continuous rumble (output report 0x80, 10 bytes). Speeds are 0..65535. */
    fun usbRumble(left: Int, right: Int): ByteArray
    {
        val l = left.coerceIn(0, 0xFFFF); val r = right.coerceIn(0, 0xFFFF)
        val m = maxOf(l, r)
        return byteArrayOf(
            OUT_HAPTIC_RUMBLE.toByte(), 0,
            (m and 0xFF).toByte(), ((m shr 8) and 0xFF).toByte(),
            (l and 0xFF).toByte(), ((l shr 8) and 0xFF).toByte(), 0,
            (r and 0xFF).toByte(), ((r shr 8) and 0xFF).toByte(), 0
        )
    }

    /** BLE vendor-characteristic haptic pulse: [0x8F, pad, high_us, low_us, repeat] (all u16 LE). */
    fun blePulse(pad: Int, magnitude: Int): ByteArray
    {
        val mag = magnitude.coerceIn(0, 0xFFFF)
        if(mag == 0)
            return byteArrayOf(0x8F.toByte(), pad.toByte(), 0, 0, 0, 0, 0, 0)
        val period = 6250 // ~160 Hz, near the actuator's resonance
        val duty = 25 + mag * (97 - 25) / 0xFFFF
        val high = (period * duty / 100).coerceIn(1, period - 1)
        val low = period - high
        return byteArrayOf(
            0x8F.toByte(), pad.toByte(),
            (high and 0xFF).toByte(), ((high shr 8) and 0xFF).toByte(),
            (low and 0xFF).toByte(), ((low shr 8) and 0xFF).toByte(),
            0xFF.toByte(), 0xFF.toByte()
        )
    }
}
