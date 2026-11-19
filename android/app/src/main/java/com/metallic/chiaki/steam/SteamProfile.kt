package com.metallic.chiaki.steam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What a Steam input can be mapped to on the PlayStation side. */
enum class PsTarget(val label: String, val isAction: Boolean = false)
{
    NONE("(nothing)"),
    CROSS("✕ Cross"), CIRCLE("◯ Circle"), SQUARE("▢ Square"), TRIANGLE("△ Triangle"),
    L1("L1"), R1("R1"), L2("L2 (full press)"), R2("R2 (full press)"), L3("L3 (left stick click)"), R3("R3 (right stick click)"),
    OPTIONS("Options"), SHARE("Create / Share"), PS("PS button (guide)"), TOUCHPAD("Touchpad click"),
    DPAD_UP("D-pad up"), DPAD_DOWN("D-pad down"), DPAD_LEFT("D-pad left"), DPAD_RIGHT("D-pad right"),
    ACT_MIC_MUTE("Action: mute / unmute headset mic", true),
    ACT_GYRO_TOGGLE("Action: gyro on / off", true),
    ACT_PAD_MODE_NEXT("Action: next touchpad mode", true);

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: NONE }
}

enum class PadMode(val label: String, val help: String)
{
    JOINT_SPLIT("Joint split", "Left pad drives the left half of the DualSense touchpad, right pad drives the right half."),
    DOUBLE("Double", "Each pad covers the whole touchpad. Use the full area with one thumb, or both thumbs at once."),
    LEFT_ONLY("Left pad only", "Only the left pad is used, covering the whole touchpad."),
    RIGHT_ONLY("Right pad only", "Only the right pad is used, covering the whole touchpad."),
    OFF("Off", "Pads do not send touch input (clicks can still be mapped).");

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: JOINT_SPLIT }
}

enum class GyroMode(val label: String)
{
    OFF("Off"), ALWAYS("Always on"), WHILE_HELD("Only while a chosen input is held");
    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: OFF }
}

/** Output axis <- input axis (0=X, 1=Y, 2=Z) with optional invert. Used to fix IMU orientation. */
data class AxisMap(val src: Int, val invert: Boolean)

data class SteamProfile(
    val name: String,
    val mapping: Map<SteamSource, PsTarget> = defaultMapping(),
    val padMode: PadMode = PadMode.JOINT_SPLIT,
    val padInvertY: Boolean = true,
    val padInvertX: Boolean = false,
    val padRangeX: Int = 32767,      // raw pad half-extent that maps to the touchpad edge
    val padRangeY: Int = 32767,
    val leftDeadzone: Int = 8,       // percent
    val rightDeadzone: Int = 8,
    val triggerDeadzone: Int = 2,    // percent
    val gyroMode: GyroMode = GyroMode.OFF,
    val gyroHoldSource: SteamSource = SteamSource.GRIP_RT,
    val gyroSensitivity: Int = 100,  // percent
    val gyroMapX: AxisMap = AxisMap(0, false),   // -> DualSense X
    val gyroMapY: AxisMap = AxisMap(2, false),   // -> DualSense Y
    val gyroMapZ: AxisMap = AxisMap(1, true),    // -> DualSense Z
    val rumbleIntensity: Int = 100,  // percent
    val touchpadHaptics: Boolean = true // click haptics on trackpads
)
{
    fun target(src: SteamSource) = mapping[src] ?: PsTarget.NONE

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("mapping", JSONObject().also { m -> mapping.forEach { (k, v) -> m.put(k.name, v.name) } })
        put("padMode", padMode.name)
        put("padInvertY", padInvertY); put("padInvertX", padInvertX)
        put("padRangeX", padRangeX); put("padRangeY", padRangeY)
        put("leftDeadzone", leftDeadzone); put("rightDeadzone", rightDeadzone); put("triggerDeadzone", triggerDeadzone)
        put("gyroMode", gyroMode.name); put("gyroHoldSource", gyroHoldSource.name); put("gyroSensitivity", gyroSensitivity)
        put("gyroMapX", axis(gyroMapX)); put("gyroMapY", axis(gyroMapY)); put("gyroMapZ", axis(gyroMapZ))
        put("rumbleIntensity", rumbleIntensity)
        put("touchpadHaptics", touchpadHaptics)
    }

    companion object
    {
        private fun axis(a: AxisMap) = JSONObject().put("src", a.src).put("invert", a.invert)
        private fun axis(o: JSONObject?, d: AxisMap) = if(o == null) d else AxisMap(o.optInt("src", d.src).coerceIn(0, 2), o.optBoolean("invert", d.invert))

        fun defaultMapping(): Map<SteamSource, PsTarget> = mapOf(
            SteamSource.A to PsTarget.CROSS, SteamSource.B to PsTarget.CIRCLE,
            SteamSource.X to PsTarget.SQUARE, SteamSource.Y to PsTarget.TRIANGLE,
            SteamSource.LB to PsTarget.L1, SteamSource.RB to PsTarget.R1,
            SteamSource.DPAD_UP to PsTarget.DPAD_UP, SteamSource.DPAD_DOWN to PsTarget.DPAD_DOWN,
            SteamSource.DPAD_LEFT to PsTarget.DPAD_LEFT, SteamSource.DPAD_RIGHT to PsTarget.DPAD_RIGHT,
            SteamSource.LS to PsTarget.L3, SteamSource.RS to PsTarget.R3,
            SteamSource.MENU to PsTarget.OPTIONS, SteamSource.VIEW to PsTarget.SHARE,
            SteamSource.STEAM to PsTarget.PS,
            SteamSource.QUICK_ACCESS to PsTarget.ACT_MIC_MUTE,
            SteamSource.TP_LT_CLICK to PsTarget.TOUCHPAD, SteamSource.TP_RT_CLICK to PsTarget.TOUCHPAD
        )

        fun fromJson(o: JSONObject): SteamProfile
        {
            val d = SteamProfile(o.optString("name", "Default"))
            val map = defaultMapping().toMutableMap()
            o.optJSONObject("mapping")?.let { m ->
                // A saved mapping is complete: anything not listed means "nothing".
                map.clear()
                m.keys().forEach { k ->
                    val src = SteamSource.byName(k) ?: return@forEach
                    map[src] = PsTarget.byName(m.optString(k))
                }
            }
            return d.copy(
                mapping = map,
                padMode = PadMode.byName(o.optString("padMode")),
                padInvertY = o.optBoolean("padInvertY", d.padInvertY), padInvertX = o.optBoolean("padInvertX", d.padInvertX),
                padRangeX = o.optInt("padRangeX", d.padRangeX).coerceIn(1000, 32767),
                padRangeY = o.optInt("padRangeY", d.padRangeY).coerceIn(1000, 32767),
                leftDeadzone = o.optInt("leftDeadzone", d.leftDeadzone).coerceIn(0, 40),
                rightDeadzone = o.optInt("rightDeadzone", d.rightDeadzone).coerceIn(0, 40),
                triggerDeadzone = o.optInt("triggerDeadzone", d.triggerDeadzone).coerceIn(0, 40),
                gyroMode = GyroMode.byName(o.optString("gyroMode")),
                gyroHoldSource = SteamSource.byName(o.optString("gyroHoldSource")) ?: d.gyroHoldSource,
                gyroSensitivity = o.optInt("gyroSensitivity", d.gyroSensitivity).coerceIn(10, 400),
                gyroMapX = axis(o.optJSONObject("gyroMapX"), d.gyroMapX),
                gyroMapY = axis(o.optJSONObject("gyroMapY"), d.gyroMapY),
                gyroMapZ = axis(o.optJSONObject("gyroMapZ"), d.gyroMapZ),
                rumbleIntensity = o.optInt("rumbleIntensity", d.rumbleIntensity).coerceIn(0, 100),
                touchpadHaptics = o.optBoolean("touchpadHaptics", d.touchpadHaptics)
            )
        }
    }
}

enum class TransportChoice { USB, BLUETOOTH }

/** App-wide (not per-profile) settings plus the profile list, kept in one SharedPreferences file. */
class SteamStore(context: Context)
{
    private val sp = context.applicationContext.getSharedPreferences("steam_controller", Context.MODE_PRIVATE)

    var transport: TransportChoice
        get() = if(sp.getString("transport", "USB") == "BLUETOOTH") TransportChoice.BLUETOOTH else TransportChoice.USB
        set(v) { sp.edit().putString("transport", v.name).apply() }

    var bluetoothAddress: String?
        get() = sp.getString("bt_address", null)
        set(v) { sp.edit().putString("bt_address", v).apply() }

    /** Bridge mode: don't decode/draw video, use the lowest stream profile. The phone is only a controller link. */
    var videoDisabled: Boolean
        get() = sp.getBoolean("video_disabled", false)
        set(v) { sp.edit().putBoolean("video_disabled", v).apply() }

    var micEnabled: Boolean
        get() = sp.getBoolean("mic_enabled", true)
        set(v) { sp.edit().putBoolean("mic_enabled", v).apply() }

    var micStartMuted: Boolean
        get() = sp.getBoolean("mic_start_muted", false)
        set(v) { sp.edit().putBoolean("mic_start_muted", v).apply() }

    var bleGyroExperiment: Boolean
        get() = sp.getBoolean("ble_gyro_experiment", false)
        set(v) { sp.edit().putBoolean("ble_gyro_experiment", v).apply() }

    var activeProfileName: String
        get() = sp.getString("active_profile", "Default") ?: "Default"
        set(v) { sp.edit().putString("active_profile", v).apply() }

    fun profiles(): List<SteamProfile>
    {
        val raw = sp.getString("profiles", null)
        val list = mutableListOf<SteamProfile>()
        if(raw != null)
        {
            try
            {
                val arr = JSONArray(raw)
                for(i in 0 until arr.length()) list.add(SteamProfile.fromJson(arr.getJSONObject(i)))
            }
            catch(_: Exception) {}
        }
        if(list.none { it.name == "Default" }) list.add(0, SteamProfile("Default"))
        return list
    }

    fun saveProfiles(list: List<SteamProfile>)
    {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    fun activeProfile(): SteamProfile = profiles().let { l -> l.firstOrNull { it.name == activeProfileName } ?: l.first() }

    fun saveProfile(p: SteamProfile)
    {
        val l = profiles().toMutableList()
        val i = l.indexOfFirst { it.name == p.name }
        if(i >= 0) l[i] = p else l.add(p)
        saveProfiles(l)
    }

    fun deleteProfile(name: String)
    {
        if(name == "Default") return
        saveProfiles(profiles().filter { it.name != name })
        if(activeProfileName == name) activeProfileName = "Default"
    }

    /** One JSON document with everything, for backup / moving to a new phone. */
    fun exportJson(): String = JSONObject().apply {
        put("version", 1)
        put("active", activeProfileName)
        put("transport", transport.name)
        put("videoDisabled", videoDisabled)
        put("micEnabled", micEnabled)
        put("profiles", JSONArray().also { a -> profiles().forEach { a.put(it.toJson()) } })
    }.toString(2)

    fun importJson(text: String): Boolean
    {
        return try
        {
            val o = JSONObject(text)
            val arr = o.getJSONArray("profiles")
            val list = (0 until arr.length()).map { SteamProfile.fromJson(arr.getJSONObject(it)) }
            if(list.isEmpty()) return false
            saveProfiles(list)
            activeProfileName = o.optString("active", list.first().name)
            if(o.has("videoDisabled")) videoDisabled = o.optBoolean("videoDisabled")
            if(o.has("micEnabled")) micEnabled = o.optBoolean("micEnabled")
            true
        }
        catch(e: Exception) { false }
    }
}
