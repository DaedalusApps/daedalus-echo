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
    - `com.daedalus.echo.ai`: Local LLM (MediaPipe) and model downloading logic.
    - `com.daedalus.echo.recording`: Audio recording management (built-in and Bluetooth SCO microphone).
    - `com.daedalus.echo.data`: Room database for recordings and metadata.
    - `com.daedalus.echo.ui`: Compose-based screens and theme.
    - `com.daedalus.echo.viewmodel`: State management using ViewModels and StateFlow.

## Key Design Decisions
- **On-Device AI**: Uses MediaPipe LLM Inference with the Gemma 3 1B model in `.task` format. Models are downloaded on first launch or via Settings.
- **Microphone Recording**: Supports high-quality audio recording via built-in device microphone or a connected Bluetooth headset microphone (using Bluetooth SCO).
- **Local Import**: Allows importing existing audio recordings from local storage or external USB OTG drives using the Storage Access Framework (SAF).
