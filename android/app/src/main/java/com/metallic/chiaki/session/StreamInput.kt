package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.steam.SteamController

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		val merged = controllerState or touchControllerState

		// Steam Controller (read directly over USB/BLE, mapped to DualSense semantics)
		val steam = steamControllerState
		if(steam != null)
		{
			val combined = merged or steam
			// While a Steam Controller is connected its IMU is the only motion source:
			// real data when gyro is active, neutral otherwise (never the phone's sensors).
			combined.gyroX = steam.gyroX; combined.gyroY = steam.gyroY; combined.gyroZ = steam.gyroZ
			combined.accelX = steam.accelX; combined.accelY = steam.accelY; combined.accelZ = steam.accelZ
			combined.orientX = steam.orientX; combined.orientY = steam.orientY; combined.orientZ = steam.orientZ; combined.orientW = steam.orientW
			return combined
		}
		return merged
	}

	@Volatile private var steamControllerState: ControllerState? = null

	/** Start receiving mapped Steam Controller frames. */
	private val steamCallback: (ControllerState) -> Unit = {
		steamControllerState = it
		controllerStateUpdated()
	}

	fun attachSteamController()
	{
		SteamController.onControllerState = steamCallback
	}

	/** The controller went away mid-stream: release everything it was holding. */
	fun clearSteamState()
	{
		steamControllerState = ControllerState()
		controllerStateUpdated()
	}

	fun detachSteamController()
	{
		// A restarted stream attaches its own input before this one is destroyed; don't undo that.
		if(SteamController.onControllerState === steamCallback)
			SteamController.onControllerState = null
		steamControllerState = null
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon

	private var lastOrientX = 0f
	private var lastOrientY = 0f
	private var lastOrientZ = 0f
	private var lastOrientW = 1f
	private var hasOrient = false

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					val rawX = event.values[1]
					val rawY = event.values[2]
					val rawZ = event.values[0]
					val deadzone = 0.02f
					sensorControllerState.gyroX = if(kotlin.math.abs(rawX) < deadzone) 0f else rawX
					sensorControllerState.gyroY = if(kotlin.math.abs(rawY) < deadzone) 0f else rawY
					sensorControllerState.gyroZ = if(kotlin.math.abs(rawZ) < deadzone) 0f else rawZ
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					val targetX = q[2]
					val targetY = q[3]
					val targetZ = q[1]
					val targetW = q[0]
					if(!hasOrient)
					{
						lastOrientX = targetX; lastOrientY = targetY; lastOrientZ = targetZ; lastOrientW = targetW
						hasOrient = true
					}
					else
					{
						val alpha = 0.2f
						lastOrientX += alpha * (targetX - lastOrientX)
						lastOrientY += alpha * (targetY - lastOrientY)
						lastOrientZ += alpha * (targetZ - lastOrientZ)
						lastOrientW += alpha * (targetW - lastOrientW)
					}
					sensorControllerState.orientX = lastOrientX
					sensorControllerState.orientY = lastOrientY
					sensorControllerState.orientZ = lastOrientZ
					sensorControllerState.orientW = lastOrientW
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val keyCode = event.keyCode
		val action = event.action == KeyEvent.ACTION_DOWN

		// Check for L2/R2 (can be digital or analog, here we handle digital key event)
		if (keyCode == preferences.mappingL2) {
			keyControllerState.l2State = if(action) UByte.MAX_VALUE else 0U
			controllerStateUpdated()
			return true
		}
		if (keyCode == preferences.mappingR2) {
			keyControllerState.r2State = if(action) UByte.MAX_VALUE else 0U
			controllerStateUpdated()
			return true
		}

		val buttonMask: UInt = when(keyCode)
		{
			preferences.mappingCross -> ControllerState.BUTTON_CROSS
			preferences.mappingCircle -> ControllerState.BUTTON_MOON
			preferences.mappingSquare -> ControllerState.BUTTON_BOX
			preferences.mappingTriangle -> ControllerState.BUTTON_PYRAMID
			preferences.mappingL1 -> ControllerState.BUTTON_L1
			preferences.mappingR1 -> ControllerState.BUTTON_R1
			preferences.mappingL3 -> ControllerState.BUTTON_L3
			preferences.mappingR3 -> ControllerState.BUTTON_R3
			preferences.mappingShare -> ControllerState.BUTTON_SHARE
			preferences.mappingOptions -> ControllerState.BUTTON_OPTIONS
			preferences.mappingPs -> ControllerState.BUTTON_PS
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			if(action) this or buttonMask else this and buttonMask.inv()
		}

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		motionControllerState.l2State = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).unsignedAxis()
		motionControllerState.r2State = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}