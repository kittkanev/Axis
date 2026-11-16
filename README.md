<div align="center">

<img src="docs/axis-logo.png" width="120" height="120" alt="Axis logo">

# Axis for Android

An open-source Android assistant with a floating voice panel and models you connect with your own API key.

[![Download APK](https://img.shields.io/badge/Download-APK-ffab98?style=for-the-badge&logo=android&logoColor=171321)](https://github.com/kittkanev/Axis/releases)
[![Build status](https://img.shields.io/github/actions/workflow/status/kittkanev/Axis/android.yml?branch=main&style=for-the-badge&label=Android%20CI)](https://github.com/kittkanev/Axis/actions/workflows/android.yml)
[![Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-c4b5ff?style=for-the-badge)](LICENSE)

**Android 10 and later**

</div>

## Get started

1. Download an APK from [GitHub Releases](https://github.com/kittkanev/Axis/releases) and open it on your phone. If the release offers **Axis Preview**, it installs alongside Axis with a separate workspace.
2. Open **Axis setup (Настройка Axis)** in the app menu for an interactive checklist. In **Models (Модели)**, choose Google AI Studio, OpenAI, OpenRouter, DeepSeek, Groq, or your own OpenAI-compatible HTTPS endpoint. Enter your API key and select a model. Each profile has its own system prompt, voice, and enabled tools.
3. Tap **Set Axis as assistant (Сделать Axis ассистентом)** and confirm in Android. On phones that expose the Android assistant gesture, swipe inward from a bottom corner. You can also use the Axis Quick Settings tile, the **Voice (Голос)** button, or **Ctrl + Shift + Space** inside Axis.
4. For a panel that stays with you when you switch apps, open **Overlay (Оверлей)** and choose **Show over apps (Показать поверх приложений)**. Use the microphone to record or cancel a recording, the gear for overlay settings, and the close button to dismiss the panel. Drag the sphere or the header of the expanded panel to move it. Axis saves the position.

## POCO, Xiaomi, and Redmi

If the panel disappears when you switch apps on MIUI or HyperOS:

1. In **Overlay (Оверлей)**, enable **Accessibility overlay (Панель через специальные возможности)**, choose **Connect Axis service (Подключить службу Axis)**, and enable Axis in Android Accessibility. Return to Axis and show the floating panel.
2. If the firmware still interrupts the panel, open Axis's **App info → Other permissions** and allow background pop-up windows if the option is available. Under battery settings, choose **No restrictions** for Axis.
3. If the launcher does not send the system assistant gesture, configure a supported shortcut in Android or use the Axis Quick Settings tile.

Paths and labels vary by MIUI and HyperOS version. The overlay hides when the phone is locked. Android can restrict ordinary overlays on protected screens.

## Voice, screen actions, and files

- **Voice.** Android handles dictation. Axis can send the finished phrase after a pause. In **Models → Model voice (Голос модели)**, Google AI Studio profiles offer conversational Gemini speech with four delivery styles and a voice preview. Android voice and playback settings are also available.
- **Wake phrase.** In **Settings → Wake phrase (Активация по фразе)**, enable **Listen for “Аксис” or “Axis”** and select the recognition language. Say the phrase alone to start dictation or say it followed by your command. The wake listener requires Android 12 or later and a supported on-device speech recognizer with the language installed. It runs while the floating panel is active and the screen is unlocked. Close the floating panel to stop listening.
- **Automatic screen actions.** Enable the Axis accessibility service and **Automatic actions without confirmation (Автовыполнение без подтверждений)** in Settings. A model with tool calling can open an app and continue with taps, text entry, or scrolling across its screens. Axis sends the current control labels for each step and offers **Stop request (Остановить запрос)** during execution. Try “Open Settings and go to About phone” for a system shortcut that works without an API key.
- **Screen actions with confirmation.** Enable the Axis accessibility service. In the floating panel choose **Screen command (Команда экрану)**, select the screen and enter a command. Axis sends the request and visible control labels to the selected model. Confirm its proposed tap, text entry, scroll, or Back action. Axis rechecks the target window before acting. The **Device control (Управление)** screen also accepts manual plans, one action per line.
- **Files.** Open UTF-8 TXT and Markdown files up to 128 KB through Android's file picker. Edit the text and save a separate copy.
- **Chats.** Replies stream as they arrive and support Markdown and copying. Open **Conversations** to create, rename, delete, or search the active profile's conversations.
- **Axis Cloud.** Connect your HTTPS Axis backend for encrypted workspace sync, reminders, releases, hosted DeepSeek, and Piper speech. The app can use a private CA for local deployments. [Android integration](ANDROID_INTEGRATION.md) · [Server setup](server/README.md)

API keys are protected by Android Keystore. A normal model request sends the prompt and recent conversation to the selected provider. Gemini speech sends the reply text in a separate request. Axis does not send screenshots or document contents.

## Build from source

Install JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. Set `ANDROID_HOME` or configure Android Studio.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. Use `./gradlew :app:assembleRelease -PaxisPreview=0.6.0` to build this side-by-side Preview edition. `assembleRelease` produces an unsigned APK. In-place upgrades require the matching package ID and signing key.

Axis is released under [Apache License 2.0](LICENSE).
