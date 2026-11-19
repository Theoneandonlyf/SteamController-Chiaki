package com.metallic.chiaki.steam.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.steam.*

/** Live view of what the controller sends and what the PS5 will see. Use it to check axes, pads and the IMU. */
class SteamDebugActivity : AppCompatActivity()
{
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val tick = object : Runnable
    {
        override fun run()
        {
            text.text = render()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        title = "Steam Controller – live"
        SteamController.init(applicationContext)
        val (sv, col) = scrollColumn()
        text = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        col.buttonRow(
            "Reset pad range" to { SteamController.resetPadStats() },
            "Apply pad range to profile" to { applyRange() }
        )
        col.buttonRow("Test rumble" to { SteamController.testRumble() })
        col.addView(text)
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun applyRange()
    {
        val x = SteamController.padMaxX
        val y = SteamController.padMaxY
        if(x < 4000 || y < 4000) { Toast.makeText(this, "Sweep both pads edge to edge first", Toast.LENGTH_LONG).show(); return }
        val store = SteamStore(this)
        store.saveProfile(store.activeProfile().copy(padRangeX = x.coerceIn(1000, 32767), padRangeY = y.coerceIn(1000, 32767)))
        SteamController.reloadProfile()
        Toast.makeText(this, "Pad range set to $x × $y", Toast.LENGTH_SHORT).show()
    }

    private fun render(): String
    {
        val sb = StringBuilder()
        sb.append("status: ${SteamController.status}\n")
        SteamController.lastError?.let { sb.append("error: $it\n") }
        SteamController.battery?.let { sb.append("battery: ${it.percent}% (state ${it.chargeState})\n") }
        sb.append("frames: ${SteamController.frameCount}\n")
        val s = SteamController.lastState
        if(s == null) { sb.append("\n(no data yet)"); return sb.toString() }

        sb.append("\nbuttons: 0x${Integer.toHexString(s.buttons)}\n")
        sb.append("held: " + SteamSource.values().filter { s.has(it.mask) }.joinToString(", ") { it.label } + "\n")
        sb.append("\nL stick  ${s.leftX}, ${s.leftY}\nR stick  ${s.rightX}, ${s.rightY}\n")
        sb.append("L trig   ${s.leftTrigger}   R trig ${s.rightTrigger}\n")
        sb.append("\nL pad    ${s.leftPadX}, ${s.leftPadY}  contact ${s.leftPadContact}\n")
        sb.append("R pad    ${s.rightPadX}, ${s.rightPadY}  contact ${s.rightPadContact}\n")
        sb.append("pad max seen: ${SteamController.padMaxX} × ${SteamController.padMaxY}\n")
        sb.append("\nIMU valid: ${s.imuValid}\n")
        if(s.imuValid)
        {
            sb.append("accel ${s.accelX}, ${s.accelY}, ${s.accelZ}\n")
            sb.append("gyro  ${s.gyroX}, ${s.gyroY}, ${s.gyroZ}\n")
            sb.append("|accel| = ${"%.2f".format(s.accelMagnitudeG())} g  (should be ≈1.00 when held still;\n  if not, the IMU scale assumption is off)\n")
        }
        else sb.append("(no IMU data: normal over Bluetooth unless the experimental option works)\n")
        sb.append("\nmapper:\n  pad mode: ${SteamController.mapper.effectivePadMode.label}\n  gyro active: ${SteamController.mapper.gyroActive}\n")
        return sb.toString()
    }
}
