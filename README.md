# SonAI - Intelligent Focus Zone (Mobile & WearOS)

SonAI is a modern Android application designed to improve concentration and privacy by adaptively masking ambient noise. Unlike static white noise machines, SonAI uses on-device AI to analyze your environment and generate natural, organic counter-noises that evolve with your surroundings.

## 🚀 Features

- **AI-Powered Analysis**: Uses MediaPipe's YAMNet model to classify ambient sounds (speech, traffic, tools, etc.) in real-time.
- **Adaptive Masking**: Automatically adjusts the type and intensity of noise based on the detected environment.
- **Organic Noise Engine**: Features a custom audio generator with multi-layered LFO (Low-Frequency Oscillator) modulation and **Multi-tonal Timbral Shifts** to ensure the sound remains natural and non-fatiguing over long periods.
- **Atmospheric Modes**: Includes **White, Pink, Brown**, and a dedicated **Rain** mode with dynamic drop density.
- **WearOS Support**: Dedicated smartwatch interface to control your focus zone directly from your wrist.
- **Modern Jetpack Compose UI**: Built entirely with Material 3 (Mobile) and Wear Compose (Smartwatch) for a fluid and accessible experience.
- **Background Protection**: Runs as a high-priority Foreground Service (Android 14+ compliant) to ensure uninterrupted focus.
- **Multi-language Support**: Fully localized in English, Italian, Spanish, French, German, and Russian.
- **Privacy First**: All audio analysis is performed locally; no audio data is ever recorded or transmitted.

## 🛠 Tech Stack

- **Language**: Kotlin 2.3.20
- **UI Framework**: Jetpack Compose (Material 3 & Wear Compose)
- **AI/ML**: MediaPipe Audio Classifier (YAMNet)
- **Audio Engine**: Low-level `AudioTrack` API for low-latency synthesis
- **Minimum SDK**: 30 (Android 11)
- **Target SDK**: 37 (Android 15)
- **Build System**: Gradle 9.1.1 (Kotlin DSL)

## 📂 Project Structure

- `app/`: Main mobile application module.
- `wear/`: Dedicated WearOS application module.
- `app/src/main/java/com/scardracs/sonai/service/`: `SoundAnalysisService` for background AI classification.
- `app/src/main/java/com/scardracs/sonai/audio/`: `NoiseGenerator` core, implementing organic synthesis and tonal modulation.
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

- **Microphone**: Used for real-time analysis (processed in memory, never stored).
- **Foreground Service**: Declared with `microphone` and `specialUse` types to comply with Android 14+ Google Play policies.
- **Attribution**: Uses `createAttributionContext` for transparent microphone usage.

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
*Created with focus by the SonAI Team.*
