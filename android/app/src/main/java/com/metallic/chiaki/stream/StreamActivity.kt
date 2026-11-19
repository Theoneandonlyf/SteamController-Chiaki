// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.metallic.chiaki.steam.MicCapture
import com.metallic.chiaki.steam.PsTarget
import com.metallic.chiaki.steam.SteamController
import com.metallic.chiaki.steam.SteamStore
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.TextView
import android.widget.Toast
import android.view.Gravity
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()

	// --- Steam Controller bridge ---
	private lateinit var steamStore: SteamStore
	private var originalConnectInfo: ConnectInfo? = null
	private var videoDisabled = false
	private var audioMuted = false
	private var bridgeStatusView: TextView? = null
	private var mic: MicCapture? = null
	private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if(granted) startMicIfWanted()
		else Toast.makeText(this, "Microphone permission denied: party chat is off", Toast.LENGTH_LONG).show()
	}
	private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
		startForegroundServiceCompat()
	}

	private fun startForegroundServiceCompat()
	{
		val serviceIntent = Intent(this, StreamForegroundService::class.java)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			startForegroundService(serviceIntent)
		else
			startService(serviceIntent)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		SteamController.init(applicationContext)
		SteamController.streamOpened()
		steamStore = SteamStore(this)
		originalConnectInfo = connectInfo
		videoDisabled = steamStore.videoDisabled
		// Bridge mode: ask the console for the cheapest stream; nothing is decoded or drawn.
		val effectiveInfo = if(videoDisabled)
			connectInfo.copy(videoProfile = ConnectVideoProfile.preset(VideoResolutionPreset.RES_360P, VideoFPSPreset.FPS_30, Codec.CODEC_H264))
		else connectInfo

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, effectiveInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
		setupSteamController()

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}


		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		binding.resyncButton.setOnClickListener {
			reconnect()
			toast("Resyncing audio/video...")
			showOverlay()
		}

		binding.audioSwitch.isChecked = true
		binding.audioSwitch.setOnCheckedChangeListener { _, isChecked ->
			audioMuted = !isChecked
			viewModel.session.session?.setAudioMuted(audioMuted)
			toast(if(audioMuted) "Audio muted" else "Audio active")
			showOverlay()
		}

binding.videoSwitch.isChecked = !videoDisabled
		binding.videoSwitch.setOnCheckedChangeListener { _, isChecked ->
			if(isChecked == !videoDisabled)
				return@setOnCheckedChangeListener
			steamStore.videoDisabled = !isChecked
			// The stream profile is fixed at connect time, so restart the session with the new setting.
			val info = originalConnectInfo
			finish()
			if(info != null)
				startActivity(Intent(this, StreamActivity::class.java).putExtra(EXTRA_CONNECT_INFO, info))
		}

		// Setup video output based on debanding preference
		setupVideoOutput()
		
		val prefs = Preferences(this)
		if (prefs.touchscreenTouchpadEnabled) {
			binding.streamTouchpadView.visibility = View.VISIBLE
			binding.streamTouchpadView.controllerState
				.subscribe { 
					lastStreamTouchpadControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
		}

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
		viewModel.session.rumbleState.observe(this, Observer {
			// Rumble goes to the Steam Controller when it is connected, otherwise to the phone.
			if(SteamController.isConnected)
			{
				SteamController.rumble(it.left.toInt(), it.right.toInt())
				return@Observer
			}
			if(!Preferences(this).rumbleEnabled)
				return@Observer
			val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
			vibrator.cancel()
			if(amplitude == 0)
				return@Observer
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
			else
				vibrator.vibrate(1000)
		})
	}

	private var debandRenderer: DebandRenderer? = null

	private fun setupVideoOutput() {
		val prefs = Preferences(this)
		viewModel.session.detachSurface()

		if (videoDisabled) {
			// No surface => the native side never starts a video decoder and just drops frames.
			binding.surfaceView.visibility = View.GONE
			binding.debandSurfaceView.visibility = View.GONE
			binding.progressBar.visibility = View.GONE
			val tv = TextView(this).apply {
				setTextColor(Color.parseColor("#B0B0B0"))
				textSize = 16f
				gravity = Gravity.CENTER
				text = "Controller bridge mode\nVideo is off, play on your TV"
			}
			(binding.root as android.view.ViewGroup).addView(tv, 0, android.widget.FrameLayout.LayoutParams(
				android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
			bridgeStatusView = tv
			return
		}

		if (prefs.debandingEnabled) {
			// Use GLSurfaceView with debanding shader
			binding.surfaceView.visibility = View.GONE
			binding.debandSurfaceView.visibility = View.VISIBLE
			
			debandRenderer = DebandRenderer { surface ->
				viewModel.session.attachToSurface(surface)
			}
			
			binding.debandSurfaceView.setEGLContextClientVersion(3)
			binding.debandSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
			binding.debandSurfaceView.holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
			binding.debandSurfaceView.setRenderer(debandRenderer)
			debandRenderer!!.sharpness = prefs.sharpnessIntensity
			binding.debandSurfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
		} else {
			// Use standard SurfaceView (no shader processing)
			binding.surfaceView.visibility = View.VISIBLE
			binding.debandSurfaceView.visibility = View.GONE
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		}
	}

	private var lastFragmentControllerState = com.metallic.chiaki.lib.ControllerState()
	private var lastStreamTouchpadControllerState = com.metallic.chiaki.lib.ControllerState()

	private fun updateCombinedTouchState() {
		viewModel.input.touchControllerState = lastFragmentControllerState or lastStreamTouchpadControllerState
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerState
				.subscribe { 
					lastFragmentControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
		}
	}

	private val streamActionReceiver = object : android.content.BroadcastReceiver()
	{
		override fun onReceive(context: android.content.Context, intent: android.content.Intent)
		{
			when(intent.action)
			{
				StreamForegroundService.ACTION_END_CONNECTION -> {
					finish()
				}
				StreamForegroundService.ACTION_RESYNC -> {
					reconnect()
					toast("Resyncing audio/video...")
				}
				StreamForegroundService.ACTION_TOGGLE_AUDIO -> {
					audioMuted = !audioMuted
					viewModel.session.session?.setAudioMuted(audioMuted)
					toast(if(audioMuted) "Audio muted" else "Audio active")
				}
			}
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		if (!videoDisabled && Preferences(this).debandingEnabled) {
			binding.debandSurfaceView.onResume()
		}
		viewModel.session.resume()

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
		{
			notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
		else
		{
			startForegroundServiceCompat()
		}

		val filter = android.content.IntentFilter().apply {
			addAction(StreamForegroundService.ACTION_END_CONNECTION)
			addAction(StreamForegroundService.ACTION_RESYNC)
			addAction(StreamForegroundService.ACTION_TOGGLE_AUDIO)
		}
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			registerReceiver(streamActionReceiver, filter, RECEIVER_NOT_EXPORTED)
		else
			registerReceiver(streamActionReceiver, filter)
	}

	override fun onPause()
	{
		super.onPause()
		if (!videoDisabled && Preferences(this).debandingEnabled) {
			binding.debandSurfaceView.onPause()
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		stopMic()
		viewModel.session.pause()
		try { unregisterReceiver(streamActionReceiver) } catch(_: Exception) {}
		stopService(Intent(this, StreamForegroundService::class.java))

		if(SteamController.onAction === steamActionCallback) SteamController.onAction = null
		if(SteamController.onStatusChanged === steamStatusCallback) SteamController.onStatusChanged = null
		viewModel.input.detachSteamController()
		SteamController.streamClosed()
		debandRenderer?.release()
		controlsDisposable.dispose()
	}

	private val steamActionCallback: (PsTarget) -> Unit = { action ->
		when(action)
		{
			PsTarget.ACT_MIC_MUTE ->
			{
				val m = mic
				if(m == null) toast("Microphone is not active")
				else toast(if(m.toggleMute()) "Mic muted" else "Mic live")
			}
			PsTarget.ACT_GYRO_TOGGLE -> toast("Gyro toggled")
			PsTarget.ACT_PAD_MODE_NEXT -> toast("Touchpad mode: ${SteamController.mapper.effectivePadMode.label}")
			else -> {}
		}
	}

	private val steamStatusCallback: () -> Unit = {
		if(!SteamController.isConnected)
		{
			viewModel.input.clearSteamState()
			if(SteamController.status == SteamController.Status.DISCONNECTED)
				toast(SteamController.lastError ?: "Steam Controller disconnected")
		}
		updateBridgeStatus()
	}

	private fun setupSteamController()
	{
		viewModel.input.attachSteamController()
		SteamController.reloadProfile()

		SteamController.onAction = steamActionCallback
		SteamController.onStatusChanged = steamStatusCallback

		if(!SteamController.isConnected)
		{
			val needsBtPermission = steamStore.transport == com.metallic.chiaki.steam.TransportChoice.BLUETOOTH &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
				ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
			if(needsBtPermission)
				toast("Open Steam Controller settings once to grant Bluetooth permission")
			else
			{
				SteamController.autoStop = true
				SteamController.start()
			}
		}
		updateBridgeStatus()
	}

	private fun updateBridgeStatus()
	{
		val tv = bridgeStatusView ?: return
		val c = when(SteamController.status)
		{
			SteamController.Status.CONNECTED -> "Steam Controller connected"
			SteamController.Status.CONNECTING -> "Connecting to Steam Controller..."
			SteamController.Status.WAITING_USB_PERMISSION -> "Allow USB access for the Steam Controller"
			SteamController.Status.DISCONNECTED -> "Steam Controller not connected"
		}
		tv.text = "Controller bridge mode\nVideo is off, play on your TV\n\n$c"
	}

	private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

	private fun startMicIfWanted()
	{
		if(mic != null || !steamStore.micEnabled || !com.metallic.chiaki.lib.Session.micSupported)
			return
		if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
		{
			micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
			return
		}
		val session = viewModel.session.session ?: return
		val m = MicCapture(session)
		if(m.start(steamStore.micStartMuted))
			mic = m
		else
			toast("Could not start the microphone")
	}

	private fun stopMic()
	{
		mic?.stop()
		mic = null
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private fun showOverlay()
	{
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			// These states don't need special handling
			StreamStateConnected -> startMicIfWanted()
			StreamStateIdle, StreamStateConnecting -> { }
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
