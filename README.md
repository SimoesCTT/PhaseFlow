# PhaseFlow

A live Kuramoto-r biofeedback system for Android, using Bluetooth EEG.

Connects to Muse (Classic and Athena) and OpenBCI Ganglion headsets, computes
the Kuramoto order parameter r from live EEG, and delivers it to the user as
real-time audio and visual feedback.

## Paper

[PhaseFlow: A Live Kuramoto-R Biofeedback System for Android Using Bluetooth EEG](paper/phaseflow.tex) ([PDF](paper/phaseflow.pdf))

## Features

- Bluetooth Low Energy connection to Muse and OpenBCI Ganglion
- 4-channel EEG at 256 Hz (Muse) or 200 Hz (Ganglion)
- 4th-order Butterworth bandpass (alpha band by default)
- Hilbert transform, instantaneous phase
- Live Kuramoto order parameter r
- Audio feedback — sine tone, pitch mapped to r
- Visual feedback — bar + numeric display
- Automatic baseline capture
- No INTERNET permission

## Status

Research and educational tool. **Not a medical device.** Does not diagnose,
treat, cure, or prevent any condition.

## Building

Requires JDK 17+, Android SDK 34.

    ./gradlew assembleDebug

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
