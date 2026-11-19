# Steam Controller bridge (merged build)

This fork of chiaki-ng-android-extended reads a Steam Controller (2026) directly over USB or
Bluetooth LE and feeds it to the PS5 as a DualSense. No Shizuku, no virtual Android gamepad.

Merged from: ddeverill/SteamlessController (protocol), SonicDX12/SteamController-Android
(Android USB/BLE transport), chiaki-ng-android-extended (Remote Play).

## Build
1. `git clone --recursive https://github.com/SalamiTheMan/chiaki-ng-android-extended.git`
2. Apply the patch from the repo root: `git apply steam-bridge.patch`
3. Open the `android/` folder in Android Studio. It needs NDK 26.1.10909125 and CMake 3.22.1
   (SDK Manager > SDK Tools), plus Python 3 on PATH. First native build downloads mbedTLS.
4. Run the `app` configuration.

## First run
1. Pair/register your PS5 in the app the normal way.
2. Main screen > Settings > Steam Controller. Choose USB or Bluetooth, tap Connect.
3. Open "Live debug view" and move everything: check buttons, sticks, pads, and (USB) IMU.
4. Start a stream.

## Headset mic (party chat)
Off by default so the first build is the simplest possible. Once the app works, set
`steamMic=true` in `android/gradle.properties`, rebuild (downloads libopus), then enable
"Send headset mic to party chat" in the Steam Controller settings.

## Unverified (could not be tested without the hardware)
- IMU scale and axis orientation (adjust in settings; Debug shows |accel| which should be ~1 g)
- Pad coordinate range and Y direction (Debug screen can measure and apply the range)
- Gyro and rumble strength over Bluetooth
- The whole Opus/mic path
