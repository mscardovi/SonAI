# SonAI - Intelligent Focus Zone (Mobile & WearOS)

SonAI is a professional Android application designed to enhance concentration, relaxation and privacy. Unlike static white noise apps, SonAI uses on-device AI to analyze your environment and generate natural, organic counter-noises that evolve with your surroundings to provide the ultimate **Focus Zone**.

## 🚀 Key Features

- **AI-Powered Analysis**: Real-time classification of ambient sounds (speech, traffic, tools, etc.) using MediaPipe's YAMNet model.
- **Session Analysis & Stats**: Detailed history of focus sessions with noise classification summaries and a personalized **Focus Index** to track productivity over time.
- **Smart Adaptive Volume**: Automatically adjusts masking intensity based on ambient decibel levels. On Wear OS, it can also use **Heart Rate Biofeedback** to boost masking during detected stress spikes.
- **Organic Audio Engine**: A custom procedural synthesis engine using low-level `AudioTrack`. Features multi-layered LFO modulation and EMA-smoothed transitions for a non-fatiguing experience. Now with **Stereo 44.1kHz** support.
- **Binaural Beats Selection**: Overlay Alpha, Beta, Theta, or Delta waves to target specific mental states like deep focus, creativity, or relaxation.
- **Automation & Routines**:
  - **Auto-DND**: Automatically enables "Do Not Disturb" mode when a focus session starts to eliminate digital interruptions.
  - **Geofencing**: Automatic notifications or session suggestions when entering pre-defined "Focus Zones" (e.g., office or library).
- **Atmospheric Modes**:
  - **Deep Space**: Mechanical, dark engine hum for deep concentration.
  - **Stellar Wind**: Organic, breathy wind gusts using resonant pink noise filtering.
  - **Earth Rumble**: Subsonic tectonic rumbles, tremors, and distant storms.
  - **Rain Forest**: Dynamic rain with procedural bird chirps and distant thunder.
  - **Ocean Waves**: Slow waves with realistic, synthesized coastal seagull calls.
  - **Hybrid Auto**: A smart mix that dynamically balances all modes based on environmental needs.
- **Animated Focus Timer**: Integrated countdown timer (15–120 min). The interactive UI slider **moves automatically** as time passes, providing real-time visual progress of your session.
- **Independent Controls**: Audio playback and AI-powered Smart Focus can be controlled independently. You can enjoy manual background sounds with the microphone completely off, or activate "Auto (AI)" for intelligent environment adaptation.
- **WearOS Support**: Control your Focus Zone directly from your wrist with a dedicated smartwatch interface. Features bidirectional **Real-time Sync** (Play/Pause/Status) between phone and watch.
- **Modern UI**: Built with Jetpack Compose (Material 3) featuring real-time decibel waveform visualization.
- **Material 3 Expressive (Wear OS)**: The smartwatch interface has been migrated to the latest Material 3 Expressive and ProtoLayout APIs for a modern, high-performance experience.
- **Global Reach**: Fully localized in English, Italian, Spanish, French, German, Russian, Chinese (Simplified), and Japanese.
- **Privacy First**: Local on-device analysis only. Microphone is only active when "Auto (AI)" is selected. No audio data is ever recorded, stored, or transmitted. See our [Privacy Policy](PRIVACY.md).

## 📱 Screenshots

| Light Mode | Dark Mode | Wear OS Tile |
|:---:|:---:|:---:|
| <img src="screenshots/light_mode.png" width="240"> | <img src="screenshots/dark_mode.png" width="240"> | <img src="screenshots/wear_tile.png" width="240"> |

## 🛠 Tech Stack

- **Language**: Kotlin 2.3.21
- **UI Framework**: Jetpack Compose (Material 3 & Wear Compose)
- **AI/ML**: MediaPipe Audio Classifier (YAMNet)
- **Database**: Room for focus statistics and session history.
- **Location**: Play Services Geofencing for location-based automation.
- **Health**: Health Services for real-time heart rate biofeedback on Wear OS and **Health Connect** for historical health data integration.
- **Audio Engine**: Procedural synthesis via `AudioTrack` (Stereo PCM 16-bit, 44.1kHz)
- **Minimum SDK**: 30 (Android 11)
- **Target SDK**: 37 (Android 17)
- **Build System**: Gradle 9.2.0 (Kotlin DSL)
- **KSP**: 2.3.7 (Optimized for Kotlin 2.3.x)

### Code Quality & Maintenance
- **Static Analysis**: [Detekt](https://detekt.dev/) for Kotlin code smell detection and style consistency. All modules pass the standard configuration.
- **Android 14+ Ready**: Fully compliant with Foreground Service Type declarations (`microphone` and `specialUse`) and data attribution requirements.
- **Memory Management**: [LeakCanary](https://square.github.io/leakcanary/) (debug only) for automatic memory leak detection.

## 📂 Project Structure

- `app/`: Main mobile application module.
- `wear/`: Dedicated WearOS application module.
- `app/src/main/java/com/scardracs/sonai/service/`: `SoundAnalysisService` for background AI classification, adaptive volume, and real-time timer synchronization.
- `app/src/main/java/com/scardracs/sonai/audio/`: `NoiseGenerator` core, implementing organic synthesis, per-sample volume fading, and high-fidelity DSP logic.
- `app/src/main/assets/`: Contains the `yamnet.tflite` model.

## 🚦 Getting Started

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-repo/SonAI.git
   ```
2. **Open in Android Studio**:
   Requires Android Studio Ladybug (2024.2.1) or newer.
3. **Build and Run**:
   Install the `:app` on your phone and `:wear` on your smartwatch.

## 🛡 Permissions & Compliance

- **Microphone**: Used for real-time analysis (processed in memory, never stored). Only active in "Auto (AI)" mode.
- **Location**: Used for Geofencing to detect when you enter focus zones (processed locally).
- **Body Sensors**: Used for heart rate biofeedback on Wear OS.
- **Do Not Disturb**: Requires policy access to enable Auto-DND during focus sessions.
- **Foreground Service**: Declared with `microphone` and `specialUse` types to comply with Android 14+ Google Play policies.
- **Attribution**: Uses `createAttributionContext` and `sound_analysis` tags for transparent microphone usage.

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
*Created with focus by the SonAI Team.*
