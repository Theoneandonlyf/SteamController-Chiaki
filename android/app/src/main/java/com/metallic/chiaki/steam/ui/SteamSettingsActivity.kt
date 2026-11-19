package com.metallic.chiaki.steam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.steam.*

/** Everything about the Steam Controller bridge in one scrolling screen. */
class SteamSettingsActivity : AppCompatActivity()
{
    private lateinit var store: SteamStore
    private lateinit var profile: SteamProfile
    private lateinit var root: LinearLayout
    private var statusView: TextView? = null

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) SteamController.start() else toast("Bluetooth permission is needed to connect over Bluetooth")
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) try
        {
            contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
            toast("Exported")
        } catch(e: Exception) { toast("Export failed: ${e.message}") }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) try
        {
            val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
            if(store.importJson(text)) { toast("Imported"); reloadAndBuild() } else toast("That file is not a valid settings export")
        } catch(e: Exception) { toast("Import failed: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        title = "Steam Controller"
        SteamController.init(applicationContext)
        store = SteamStore(this)
        val (sv, col) = scrollColumn()
        root = col
        setContentView(sv)
        reloadAndBuild()
    }

    override fun onResume()
    {
        super.onResume()
        SteamController.onStatusChanged = { updateStatus() }
        updateStatus()
    }

    override fun onPause()
    {
        super.onPause()
        SteamController.onStatusChanged = null
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun reloadAndBuild()
    {
        profile = store.activeProfile()
        SteamController.reloadProfile()
        build()
    }

    private fun update(newProfile: SteamProfile, rebuild: Boolean = false)
    {
        profile = newProfile
        store.saveProfile(profile)
        SteamController.reloadProfile()
        if(rebuild) build()
    }

    private fun updateStatus()
    {
        val v = statusView ?: return
        val st = when(SteamController.status)
        {
            SteamController.Status.CONNECTED -> "Connected"
            SteamController.Status.CONNECTING -> "Connecting…"
            SteamController.Status.WAITING_USB_PERMISSION -> "Waiting for USB permission (accept the prompt)"
            SteamController.Status.DISCONNECTED -> "Not connected"
        }
        val b = SteamController.battery?.let { "\nBattery: ${it.percent}%" + if(it.chargeState == 2) " (charging)" else "" } ?: ""
        val e = SteamController.lastError?.let { "\n$it" } ?: ""
        v.text = "Status: $st$b$e"
    }

    @SuppressLint("MissingPermission")
    private fun build()
    {
        root.removeAllViews()

        // ---------------- Connection
        root.header("Connection")
        statusView = root.label("")
        updateStatus()
        root.label("Connect over")
        root.spinner(listOf("USB (cable or Puck dongle)", "Bluetooth LE"), store.transport.ordinal) {
            store.transport = TransportChoice.values()[it]
            SteamController.stop()
            build()
        }
        if(store.transport == TransportChoice.BLUETOOTH)
        {
            val btOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if(!btOk) root.button("Grant Bluetooth permission") { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            else
            {
                val devices = SteamController.pairedBluetoothControllers()
                if(devices.isEmpty())
                    root.note("No paired Steam Controller found. Pair it in Android's Bluetooth settings first (hold Steam + a face button to enter pairing mode), then come back.")
                else
                {
                    val sel = devices.indexOfFirst { it.address == store.bluetoothAddress }.coerceAtLeast(0)
                    if(store.bluetoothAddress == null) store.bluetoothAddress = devices[sel].address
                    root.label("Paired controller")
                    root.spinner(devices.map { "${it.name} (${it.address})" }, sel) { store.bluetoothAddress = devices[it].address }
                }
            }
        }
        root.buttonRow(
            "Connect" to {
                if(store.transport == TransportChoice.BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                else { SteamController.autoStop = false; SteamController.start() }
            },
            "Disconnect" to { SteamController.stop() },
            "Test rumble" to { SteamController.testRumble() }
        )
        root.buttonRow(
            "Live debug view" to { startActivity(Intent(this, SteamDebugActivity::class.java)) }
        )

        // ---------------- Profiles
        root.header("Profile")
        root.note("Everything below is saved per profile. Create one per game if you like.")
        val profiles = store.profiles()
        root.spinner(profiles.map { it.name }, profiles.indexOfFirst { it.name == profile.name }.coerceAtLeast(0)) {
            store.activeProfileName = profiles[it].name
            reloadAndBuild()
        }
        root.buttonRow(
            "New (copy of this)" to { askName("New profile name") { n ->
                if(n.isNotBlank() && profiles.none { it.name == n }) { store.saveProfile(profile.copy(name = n)); store.activeProfileName = n; reloadAndBuild() }
                else toast("Pick a name that isn't used yet")
            } },
            "Delete" to {
                if(profile.name == "Default") toast("The Default profile can't be deleted")
                else { store.deleteProfile(profile.name); reloadAndBuild() }
            }
        )

        // ---------------- Buttons
        root.header("Buttons")
        root.button("Edit button mapping…") { startActivity(Intent(this, SteamMappingActivity::class.java)) }
        root.note("Includes the Steam / Guide button, the back paddles, grips and pad clicks. Map any of them to a PlayStation button or an action.")

        // ---------------- Touchpads
        root.header("Touchpads (DualSense touchpad)")
        root.label("Mode")
        root.spinner(PadMode.values().map { it.label }, profile.padMode.ordinal) { update(profile.copy(padMode = PadMode.values()[it]), rebuild = true) }
        root.note(profile.padMode.help)
        root.switchRow("Invert pad Y (on = top of pad is top of touchpad)", profile.padInvertY) { update(profile.copy(padInvertY = it)) }
        root.switchRow("Invert pad X", profile.padInvertX) { update(profile.copy(padInvertX = it)) }
        root.switchRow("Touchpad click haptic feedback", profile.touchpadHaptics) { update(profile.copy(touchpadHaptics = it)) }
        root.note("Pad range: ${profile.padRangeX} × ${profile.padRangeY}. If your thumb can't reach the touchpad edges, use the Live debug view to measure and apply the real range.")

        // ---------------- Sticks / triggers
        root.header("Sticks and triggers")
        root.slider("Left stick deadzone", profile.leftDeadzone, 0, 40) { update(profile.copy(leftDeadzone = it)) }
        root.slider("Right stick deadzone", profile.rightDeadzone, 0, 40) { update(profile.copy(rightDeadzone = it)) }
        root.slider("Trigger deadzone", profile.triggerDeadzone, 0, 40) { update(profile.copy(triggerDeadzone = it)) }

        // ---------------- Gyro
        root.header("Gyro")
        root.label("Gyro mode")
        root.spinner(GyroMode.values().map { it.label }, profile.gyroMode.ordinal) { update(profile.copy(gyroMode = GyroMode.values()[it]), rebuild = true) }
        if(profile.gyroMode == GyroMode.WHILE_HELD)
        {
            root.label("Active while this is held")
            val srcs = SteamSource.values()
            root.spinner(srcs.map { it.label }, profile.gyroHoldSource.ordinal) { update(profile.copy(gyroHoldSource = srcs[it])) }
        }
        root.slider("Sensitivity", profile.gyroSensitivity, 10, 400) { update(profile.copy(gyroSensitivity = it)) }
        root.note("Axis mapping. The Steam Controller's IMU axes are not documented, so these defaults are a best guess. If aiming feels rotated or inverted in a game, change the source axis or invert here.")
        val axisNames = listOf("X", "Y", "Z")
        fun axisRow(title: String, a: AxisMap, set: (AxisMap) -> SteamProfile)
        {
            root.label(title)
            root.spinner(axisNames, a.src) { update(set(a.copy(src = it))) }
            root.switchRow("Invert", a.invert) { update(set(a.copy(invert = it))) }
        }
        axisRow("PS X (pitch) comes from", profile.gyroMapX) { profile.copy(gyroMapX = it) }
        axisRow("PS Y (yaw) comes from", profile.gyroMapY) { profile.copy(gyroMapY = it) }
        axisRow("PS Z (roll) comes from", profile.gyroMapZ) { profile.copy(gyroMapZ = it) }

        // ---------------- Rumble
        root.header("Rumble")
        root.slider("Intensity", profile.rumbleIntensity, 0, 100) { update(profile.copy(rumbleIntensity = it)) }

        // ---------------- Stream / audio
        root.header("Stream")
        root.switchRow("Video off (bridge mode)", store.videoDisabled) { store.videoDisabled = it }
        root.note("Nothing is decoded or drawn on the phone and the console is asked for its cheapest stream. Use this when you play on the TV and the phone is only the link for the controller. You can also flip it from the stream overlay.")

        root.header("Headset (party chat)")
        root.note("Game audio plays through whatever the phone is using, so a wired headset works automatically. Party chat needs the microphone path below.")
        if(!Session.micSupported)
            root.note("This build doesn't include microphone support. To enable it, set steamMic=true in android/gradle.properties and rebuild.")
        root.switchRow("Send headset mic to party chat", store.micEnabled) { store.micEnabled = it }
        root.switchRow("Start muted", store.micStartMuted) { store.micStartMuted = it }
        root.note("Map \"mute / unmute headset mic\" to any button in the mapping screen (Quick access is set by default).")

        // ---------------- Experimental
        root.header("Experimental")
        root.switchRow("Try enabling gyro over Bluetooth", store.bleGyroExperiment) { store.bleGyroExperiment = it }
        root.note("Gyro is known to work over USB. Over Bluetooth it is unverified: this sends a raw command that may or may not be accepted. The Live debug view shows whether IMU data arrives.")

        // ---------------- Backup
        root.header("Backup")
        root.buttonRow(
            "Export settings" to { exportLauncher.launch("steam-controller-settings.json") },
            "Import settings" to { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        )
    }

    private fun askName(title: String, done: (String) -> Unit)
    {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "Name" }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
}
