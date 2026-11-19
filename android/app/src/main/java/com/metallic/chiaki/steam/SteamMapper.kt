package com.metallic.chiaki.steam

import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.ControllerTouch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns Steam Controller frames into Chiaki [ControllerState]s (the DualSense view the PS5 sees),
 * applying the active [SteamProfile]: button mapping, stick/trigger shaping, touchpad modes and gyro.
 *
 * Fed from the transport thread; everything here is synchronized.
 */
class SteamMapper(
    private val profile: () -> SteamProfile,
    private val onAction: (PsTarget) -> Unit = {},
    private val onPadClick: ((left: Boolean) -> Unit)? = null
)
{
    companion object
    {
        private const val DEBOUNCE_FRAMES = 3
        private val TOUCH_W = ControllerState.TOUCHPAD_WIDTH.toInt()   // 1920
        private val TOUCH_H = ControllerState.TOUCHPAD_HEIGHT.toInt()  // 942

        // Assumed IMU scales (same family as the Steam Deck: ±2 g / ±2000 °/s on 16-bit).
        // The Debug screen shows |accel| at rest so the user can confirm this; sensitivity is adjustable.
        private const val ACCEL_LSB_PER_G = 16384f
        private const val GYRO_LSB_PER_DPS = 16f
        private const val DEG_TO_RAD = 0.017453292f
    }

    // debounce
    private var confirmed = 0
    private var pending = 0
    private var pendingFrames = 0
    private var started = false
    private var prevPressed = emptySet<SteamSource>()
    private var prevLeftPadClick = false
    private var prevRightPadClick = false

    // touchpad
    private val touchState = ControllerState() // persistent: owns the touch id counter

    // runtime toggles from mapped actions
    private var gyroUserEnabled = true
    private var padModeOverride: PadMode? = null

    // orientation
    private val madgwick = Madgwick()
    private var lastImuNanos = 0L

    @Volatile var effectivePadMode: PadMode = PadMode.JOINT_SPLIT; private set
    @Volatile var gyroActive = false; private set

    @Synchronized
    fun reset()
    {
        started = false; confirmed = 0; pending = 0; pendingFrames = 0; prevPressed = emptySet()
        prevLeftPadClick = false; prevRightPadClick = false
        touchState.touches[0].id = -1
        touchState.touches[1].id = -1
        madgwick.reset(); lastImuNanos = 0L
    }

    @Synchronized
    fun map(s: SteamState): ControllerState
    {
        val p = profile()

        // ---- debounce mechanical buttons; capacitive flags pass straight through
        val mech = s.buttons and SteamBtn.DEBOUNCED
        if(!started)
        {
            // Ignore anything already held when we connect.
            started = true; confirmed = mech; pending = mech; pendingFrames = 0
        }
        else if(mech == pending)
        {
            if(++pendingFrames >= DEBOUNCE_FRAMES) confirmed = mech
        }
        else { pending = mech; pendingFrames = 1 }
        val effective = (s.buttons and SteamBtn.DEBOUNCED.inv()) or confirmed

        // ---- buttons
        var buttons = 0U
        var l2 = 0
        var r2 = 0
        val pressed = HashSet<SteamSource>()
        for(src in SteamSource.values())
        {
            if((effective and src.mask) == 0) continue
            pressed.add(src)
            when(p.target(src))
            {
                PsTarget.CROSS -> buttons = buttons or ControllerState.BUTTON_CROSS
                PsTarget.CIRCLE -> buttons = buttons or ControllerState.BUTTON_MOON
                PsTarget.SQUARE -> buttons = buttons or ControllerState.BUTTON_BOX
                PsTarget.TRIANGLE -> buttons = buttons or ControllerState.BUTTON_PYRAMID
                PsTarget.L1 -> buttons = buttons or ControllerState.BUTTON_L1
                PsTarget.R1 -> buttons = buttons or ControllerState.BUTTON_R1
                PsTarget.L2 -> l2 = 255
                PsTarget.R2 -> r2 = 255
                PsTarget.L3 -> buttons = buttons or ControllerState.BUTTON_L3
                PsTarget.R3 -> buttons = buttons or ControllerState.BUTTON_R3
                PsTarget.OPTIONS -> buttons = buttons or ControllerState.BUTTON_OPTIONS
                PsTarget.SHARE -> buttons = buttons or ControllerState.BUTTON_SHARE
                PsTarget.PS -> buttons = buttons or ControllerState.BUTTON_PS
                PsTarget.TOUCHPAD -> buttons = buttons or ControllerState.BUTTON_TOUCHPAD
                PsTarget.DPAD_UP -> buttons = buttons or ControllerState.BUTTON_DPAD_UP
                PsTarget.DPAD_DOWN -> buttons = buttons or ControllerState.BUTTON_DPAD_DOWN
                PsTarget.DPAD_LEFT -> buttons = buttons or ControllerState.BUTTON_DPAD_LEFT
                PsTarget.DPAD_RIGHT -> buttons = buttons or ControllerState.BUTTON_DPAD_RIGHT
                else -> {}
            }
        }

        // ---- one-shot actions on the press edge
        for(src in pressed)
        {
            if(src in prevPressed) continue
            val t = p.target(src)
            if(!t.isAction) continue
            when(t)
            {
                PsTarget.ACT_GYRO_TOGGLE -> gyroUserEnabled = !gyroUserEnabled
                PsTarget.ACT_PAD_MODE_NEXT ->
                {
                    val modes = PadMode.values()
                    padModeOverride = modes[((padModeOverride ?: p.padMode).ordinal + 1) % modes.size]
                }
                else -> {}
            }
            onAction(t)
        }
        val leftClick = (effective and SteamBtn.TP_LT_CLICK) != 0
        val rightClick = (effective and SteamBtn.TP_RT_CLICK) != 0
        if(p.touchpadHaptics)
        {
            if(leftClick && !prevLeftPadClick) onPadClick?.invoke(true)
            if(rightClick && !prevRightPadClick) onPadClick?.invoke(false)
        }
        prevLeftPadClick = leftClick
        prevRightPadClick = rightClick
        prevPressed = pressed

        // ---- triggers: analog, with a small deadzone, OR'd with any digital L2/R2 mapping
        l2 = maxOf(l2, trigger(s.leftTrigger, p.triggerDeadzone))
        r2 = maxOf(r2, trigger(s.rightTrigger, p.triggerDeadzone))

        val out = ControllerState()
        out.buttons = buttons
        out.l2State = l2.toUByte()
        out.r2State = r2.toUByte()

        // ---- sticks. Steam: +Y is up. PlayStation (and Chiaki): +Y is down.
        val (lx, ly) = stick(s.leftX, s.leftY, p.leftDeadzone)
        val (rx, ry) = stick(s.rightX, s.rightY, p.rightDeadzone)
        out.leftX = lx.toShort(); out.leftY = (-ly).coerceIn(-32767, 32767).toShort()
        out.rightX = rx.toShort(); out.rightY = (-ry).coerceIn(-32767, 32767).toShort()

        // ---- touchpads
        val mode = padModeOverride ?: p.padMode
        effectivePadMode = mode

        val leftActive = (mode != PadMode.OFF && mode != PadMode.RIGHT_ONLY)
        val rightActive = (mode != PadMode.OFF && mode != PadMode.LEFT_ONLY)

        val leftTouching = leftActive && (s.has(SteamBtn.TP_LT) || s.leftPadContact > 0)
        val rightTouching = rightActive && (s.has(SteamBtn.TP_RT) || s.rightPadContact > 0)

        fun computeTouch(rawX: Int, rawY: Int, isLeft: Boolean): Pair<UShort, UShort> {
            var u = (rawX.toFloat() / p.padRangeX).coerceIn(-1f, 1f) * 0.5f + 0.5f
            var v = (rawY.toFloat() / p.padRangeY).coerceIn(-1f, 1f) * 0.5f + 0.5f
            if(p.padInvertX) u = 1f - u
            if(p.padInvertY) v = 1f - v

            val tx = when(mode)
            {
                PadMode.JOINT_SPLIT -> if(isLeft) u * 0.5f else 0.5f + u * 0.5f
                else -> u // DOUBLE, LEFT_ONLY, RIGHT_ONLY: the pad covers the whole touchpad
            }
            val x = (tx * (TOUCH_W - 1)).toInt().coerceIn(0, TOUCH_W - 1).toUShort()
            val y = (v * (TOUCH_H - 1)).toInt().coerceIn(0, TOUCH_H - 1).toUShort()
            return x to y
        }

        val activeTouches = mutableListOf<Pair<UShort, UShort>>()
        if(leftTouching) activeTouches.add(computeTouch(s.leftPadX, s.leftPadY, isLeft = true))
        if(rightTouching) activeTouches.add(computeTouch(s.rightPadX, s.rightPadY, isLeft = false))

        touchState.touches[0].id = if(activeTouches.size > 0) 0 else -1
        if(activeTouches.size > 0) {
            touchState.touches[0].x = activeTouches[0].first
            touchState.touches[0].y = activeTouches[0].second
        }

        touchState.touches[1].id = if(activeTouches.size > 1) 1 else -1
        if(activeTouches.size > 1) {
            touchState.touches[1].x = activeTouches[1].first
            touchState.touches[1].y = activeTouches[1].second
        }

        out.touches = arrayOf(touchState.touches[0].copy(), touchState.touches[1].copy())

        // ---- gyro / accel
        applyMotion(out, s, p, effective)

        return out
    }

    private fun trigger(raw: Int, deadzonePct: Int): Int
    {
        val v = raw.coerceIn(0, 32767) * 255 / 32767
        val dz = 255 * deadzonePct / 100
        if(v <= dz) return 0
        return ((v - dz) * 255 / (255 - dz)).coerceIn(0, 255)
    }

    /** Radial deadzone, rescaled so the edge of the deadzone becomes zero and full deflection stays full. */
    private fun stick(x: Int, y: Int, deadzonePct: Int): Pair<Int, Int>
    {
        val dz = 32767.0 * deadzonePct / 100.0
        if(dz <= 0.0) return x.coerceIn(-32767, 32767) to y.coerceIn(-32767, 32767)
        val mag = sqrt((x.toDouble() * x + y.toDouble() * y))
        if(mag < dz) return 0 to 0
        val scale = (mag - dz) / (32767.0 - dz) * 32767.0 / mag
        return (x * scale).toInt().coerceIn(-32767, 32767) to (y * scale).toInt().coerceIn(-32767, 32767)
    }

    private fun applyMotion(out: ControllerState, s: SteamState, p: SteamProfile, effective: Int)
    {
        val wanted = when(p.gyroMode)
        {
            GyroMode.OFF -> false
            GyroMode.ALWAYS -> true
            GyroMode.WHILE_HELD -> (effective and p.gyroHoldSource.mask) != 0
        }
        val active = wanted && gyroUserEnabled && s.imuValid
        gyroActive = active
        if(!active)
        {
            madgwick.reset(); lastImuNanos = 0L
            return // ControllerState defaults: no rotation, gravity on Y
        }

        val sens = p.gyroSensitivity / 100f
        val g = floatArrayOf(s.gyroX / GYRO_LSB_PER_DPS * DEG_TO_RAD * sens, s.gyroY / GYRO_LSB_PER_DPS * DEG_TO_RAD * sens, s.gyroZ / GYRO_LSB_PER_DPS * DEG_TO_RAD * sens)
        val a = floatArrayOf(s.accelX / ACCEL_LSB_PER_G, s.accelY / ACCEL_LSB_PER_G, s.accelZ / ACCEL_LSB_PER_G)
        fun pick(arr: FloatArray, m: AxisMap) = arr[m.src] * (if(m.invert) -1f else 1f)
        val gx = pick(g, p.gyroMapX); val gy = pick(g, p.gyroMapY); val gz = pick(g, p.gyroMapZ)
        val ax = pick(a, p.gyroMapX); val ay = pick(a, p.gyroMapY); val az = pick(a, p.gyroMapZ)

        val gyroDeadzone = 0.04f
        val realGx = if(kotlin.math.abs(gx) < gyroDeadzone) 0f else gx
        val realGy = if(kotlin.math.abs(gy) < gyroDeadzone) 0f else gy
        val realGz = if(kotlin.math.abs(gz) < gyroDeadzone) 0f else gz

        out.gyroX = realGx; out.gyroY = realGy; out.gyroZ = realGz
        out.accelX = ax; out.accelY = ay; out.accelZ = az

        val now = System.nanoTime()
        val dt = if(lastImuNanos == 0L) 0.004f else ((now - lastImuNanos) / 1e9f).coerceIn(0.0005f, 0.05f)
        lastImuNanos = now
        // Use pure gyro integration to eliminate accelerometer-induced upward drift / gliding
        madgwick.update(realGx, realGy, realGz, 0f, 0f, 0f, dt)
        out.orientW = madgwick.q0; out.orientX = madgwick.q1; out.orientY = madgwick.q2; out.orientZ = madgwick.q3
    }

    /** Madgwick's IMU filter (gyro + accel -> quaternion). Same algorithm Chiaki uses on desktop. */
    private class Madgwick
    {
        var q0 = 1f; var q1 = 0f; var q2 = 0f; var q3 = 0f
        private val beta = 0.02f

        fun reset() { q0 = 1f; q1 = 0f; q2 = 0f; q3 = 0f }

        fun update(gx: Float, gy: Float, gz: Float, ax0: Float, ay0: Float, az0: Float, dt: Float)
        {
            var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
            var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
            var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
            var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

            val an = sqrt(ax0 * ax0 + ay0 * ay0 + az0 * az0)
            if(an > 0.01f)
            {
                val ax = ax0 / an; val ay = ay0 / an; val az = az0 / an
                val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
                val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
                val _8q1 = 8f * q1; val _8q2 = 8f * q2
                val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

                var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
                var s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
                var s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
                var s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay
                val sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
                if(sn > 0f)
                {
                    s0 /= sn; s1 /= sn; s2 /= sn; s3 /= sn
                    qDot1 -= beta * s0; qDot2 -= beta * s1; qDot3 -= beta * s2; qDot4 -= beta * s3
                }
            }
            q0 += qDot1 * dt; q1 += qDot2 * dt; q2 += qDot3 * dt; q3 += qDot4 * dt
            val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
            if(n > 0f) { q0 /= n; q1 /= n; q2 /= n; q3 /= n }
        }
    }
}

/** Convenience for the Debug screen. */
fun SteamState.accelMagnitudeG(): Float
{
    val x = accelX / 16384f; val y = accelY / 16384f; val z = accelZ / 16384f
    return sqrt(x * x + y * y + z * z)
}

internal fun ControllerTouch.isDown() = id >= 0
internal fun absInt(v: Int) = abs(v)
