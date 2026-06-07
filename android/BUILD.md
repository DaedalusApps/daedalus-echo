# Build Guide (Android)

This file provides guidance for working with the Android project in the `android/` directory.

## Environment
- **JDK**: 21
- **Android Gradle Plugin (AGP)**: 8.7.3
- **Kotlin**: 2.0.21
- **Target SDK**: 35
- **Min SDK**: 26

## Commands
```bash
# Clean build
./gradlew clean

# Build Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew test

# Install on connected device
./gradlew installDebug
```

## Architecture
- **Tech Stack**: Jetpack Compose, Room (with Kapt), MediaPipe GenAI (Gemma), Media3 ExoPlayer.
- **Packages**:
    - `com.daedalus.voicenotes.ai`: Local LLM (MediaPipe) and model downloading logic.
    - `com.daedalus.voicenotes.ble`: Bluetooth management for the FW920 recorder.
    - `com.daedalus.voicenotes.data`: Room database for recordings and metadata.
    - `com.daedalus.voicenotes.ui`: Compose-based screens and theme.
    - `com.daedalus.voicenotes.viewmodel`: State management using ViewModels and StateFlow.

## Key Design Decisions
- **On-Device AI**: Uses MediaPipe LLM Inference with the Gemma 3 1B model in `.task` format. Models are downloaded on first launch or via Settings.
- **BLE Communication**: Handles the proprietary DOWAY protocol for the FW920 device (Recording start/stop, status).
- **USB OTG**: Accesses audio files via Storage Access Framework (SAF) when the recorder is connected as a mass storage device.
