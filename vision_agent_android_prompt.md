# Vision Agent Glass: Android App Build Prompt

## How to use this file

Copy everything under **"THE PROMPT"** below and paste it into an AI coding assistant, such as Claude Code, Gemini in Android Studio, Cursor, or a Claude chat. Build in Android Studio and run on your real phone, not the emulator, because the app needs a real camera.

Before you start, you need two things:

1. **Android Studio** (latest stable): https://developer.android.com/studio
2. **A free Gemini API key** from Google AI Studio: https://aistudio.google.com/app/apikey

---

## THE PROMPT

You are an expert Android developer. Build a complete, working Android app called **"Vision Agent"** for blind and low-vision users. Generate every file needed, with full code (no placeholders like "// TODO implement"), and explain where each file goes.

### 1. Goal

The phone's back camera acts as the user's eyes. The app is **silent by default**. It only captures an image and speaks when the user starts it. When the user triggers an action, the app:

1. Takes one photo from the back camera.
2. Sends it to the Google Gemini API.
3. Reads the answer aloud with Android Text-to-Speech.
4. Goes silent again.

There is **no continuous or automatic talking**, and no background analysis between requests.

### 2. Tech stack (use exactly this)

- Language: **Kotlin**
- UI: **Jetpack Compose** (Material 3)
- Camera: **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) with `ImageCapture`
- Network: **OkHttp** calling the Gemini REST API directly (do NOT use the deprecated `com.google.ai.client.generativeai` SDK)
- JSON: `org.json` (built into Android) or kotlinx.serialization
- Speech output: Android **`TextToSpeech`**
- Speech input (for "Ask" mode): Android **`SpeechRecognizer`**
- Async: Kotlin coroutines + ViewModel
- minSdk 26, targetSdk and compileSdk = latest stable
- Gradle Kotlin DSL (`build.gradle.kts`) with a version catalog

### 3. Features

There are three actions. Each runs once per trigger.

| Action | Trigger | What Gemini is asked |
|---|---|---|
| **Describe** | Big "DESCRIBE" button, or **Volume Up** key | Describe the scene for safe walking |
| **Read Text** | "READ TEXT" button, or **Volume Down** key | Read all visible text aloud |
| **Ask** | "ASK" button | User speaks a question; the photo + spoken question go to Gemini |

**Stop:** if the app is speaking or waiting, tapping any button (or pressing either volume key) first stops speech and cancels the pending request. It does not start a new one. A second press starts a new action.

**Busy guard:** while a request is in progress, ignore new requests (except Stop) so the free-tier quota is not wasted.

### 4. Gemini prompts (send these as the text part)

Insert `{LANG}` from settings ("English" or "Bangla").

**Describe:**
> You are the eyes of a blind person. This photo was taken by their phone camera pointing forward. In 2–3 short sentences: describe the scene, name obstacles or hazards with direction (left, ahead, right) and rough distance in steps, and say if the path ahead looks clear. Skip details that do not matter for safety. Plain spoken language, no markdown, no lists. Respond in {LANG}.

**Read Text:**
> Read out all readable text in this image (signs, labels, screens, documents, medicine packets, money notes) exactly as written, in natural reading order. Then, in one short sentence in {LANG}, say what the item is. If there is no readable text, say only that no text was found, in {LANG}. Plain text, no markdown.

**Ask:**
> You are the eyes of a blind person. Answer their question about this photo briefly and clearly in {LANG}, in plain spoken language with no markdown. If you cannot tell from the image, say so honestly. Question: {USER_QUESTION}

### 5. Gemini REST call

- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent`
- Headers: `x-goog-api-key: <API_KEY>`, `Content-Type: application/json`
- Body:

```json
{
  "contents": [{
    "parts": [
      { "inline_data": { "mime_type": "image/jpeg", "data": "<BASE64_JPEG>" } },
      { "text": "<PROMPT>" }
    ]
  }],
  "generationConfig": { "maxOutputTokens": 300, "temperature": 0.4 }
}
```

- Parse the answer from `candidates[0].content.parts[*].text` and join all text parts.
- `MODEL` must be a constant in one place (`GeminiClient.kt`), default `"gemini-flash-lite-latest"`, and editable in the Settings screen. Free-tier model names change, so the user can switch to any current Flash model listed in Google AI Studio.
- Timeouts: connect 15 s, read 30 s.

**Error handling.** Speak short, friendly messages in the selected language:

- No internet → "No internet connection."
- HTTP 429 → "Free limit reached. Please wait a minute and try again."
- HTTP 400/403 → "API key problem. Please check the key in settings."
- HTTP 404 → "Model not found. Please change the model name in settings."
- Timeout or other errors → "Sorry, that did not work. Please try again."
- Empty or blocked response → "I could not describe that."

### 6. Image handling

- Capture with `ImageCapture` in `CAPTURE_MODE_MINIMIZE_LATENCY`.
- Correct the rotation using the image's rotation degrees.
- Downscale so the longest side is **at most 1024 px**.
- Compress to JPEG at quality 80, then Base64 with `Base64.NO_WRAP`.
- Do all of this on `Dispatchers.Default`, never on the main thread.
- Never save images to storage.

### 7. API key security

- The key must **not** be hard-coded in source files.
- Read it from `local.properties` (`GEMINI_API_KEY=...`) into `BuildConfig.GEMINI_API_KEY` via the `buildConfigField` setting in `app/build.gradle.kts`, and make sure `local.properties` is in `.gitignore`.
- Also let the user paste a key in the Settings screen. Store it with `DataStore` (Preferences). A key entered in Settings overrides the BuildConfig one.
- Add a code comment explaining that this setup is fine for a personal prototype, but a public release should move to Firebase AI Logic or a backend server so the key is not shipped inside the APK.

### 8. Text-to-Speech

- Initialize `TextToSpeech` once in the ViewModel or a dedicated `Speaker` class; shut it down in `onCleared()`.
- Language: English → `Locale.US`; Bangla → `Locale("bn", "BD")`.
  - If Bangla is not installed (`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED`), fall back to English, speak a message telling the user to install the Bangla voice in Google Text-to-Speech settings, and offer a button that opens TTS settings (`Intent("com.android.settings.TTS_SETTINGS")`).
- Speech rate adjustable in Settings (0.75× to 1.5×, default 1.0×).
- Use `QUEUE_FLUSH` so a new answer always replaces old speech.
- Strip markdown characters (`*`, `#`, `_`, backticks) from Gemini's answer before speaking.

### 9. Accessibility (this is critical; the users are blind)

- Three **huge** buttons stacked vertically, filling the screen: DESCRIBE (top, biggest), READ TEXT, ASK. High contrast: white bold text on dark background, minimum text size 28sp.
- Every button has a clear `contentDescription` / semantics label so **TalkBack** works.
- Haptic feedback:
  - a short vibration when an action starts
  - a double vibration when the answer starts speaking
  - a long vibration on error
- While waiting for Gemini, play a soft repeating tick sound or gentle pulse vibration every 1.5 s so the user knows the app is working. Do not speak during the wait.
- A small camera preview (`PreviewView`) at the top, about 25% of screen height, so sighted helpers can check where the camera points. It can be hidden in Settings.
- A status line under the preview showing the state (Ready / Capturing / Thinking / Speaking / Error) and announced politely to TalkBack via `liveRegion`.
- Keep the screen awake while the app is in the foreground (`FLAG_KEEP_SCREEN_ON`).
- Volume keys: override `onKeyDown` in `MainActivity` for `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` **only while the app is in the foreground**, consume the event (so system volume does not change), and forward to the ViewModel.

### 10. Ask mode (voice question)

- On ASK, start `SpeechRecognizer` with `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. Use the locale from settings (`en-US` or `bn-BD`).
- Give a short beep before listening.
- When the result arrives, capture a photo immediately and send photo + question.
- If nothing is heard, speak "I didn't hear a question."
- If `SpeechRecognizer.isRecognitionAvailable()` is false, hide the ASK button and explain why in Settings.

### 11. Permissions

- `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `VIBRATE`.
- Request camera and microphone at runtime with the Compose permission launcher.
- On first launch, explain in speech and on screen why each permission is needed.
- If a permission is denied, speak a clear message and show a button to open app settings.

### 12. Settings screen

Opened from a small gear icon, with a large touch target and a content description. It contains:

- Language: English / Bangla
- Speech rate slider
- Gemini model name (text field, default `gemini-flash-lite-latest`)
- Gemini API key (password field, with a "Test key" button that sends a tiny text-only request and speaks success or failure)
- Show camera preview: on / off

Save everything with DataStore.

### 13. Project structure

```
app/src/main/java/com/visionagent/
  MainActivity.kt          // permissions, volume keys, sets Compose content
  ui/MainScreen.kt         // 3 big buttons, preview, status
  ui/SettingsScreen.kt
  ui/theme/                // high-contrast theme
  VisionViewModel.kt       // state machine: Idle, Capturing, Thinking, Speaking, Error
  camera/CameraController.kt  // CameraX setup + single-shot capture -> Bitmap
  ai/GeminiClient.kt       // REST call, prompts, error mapping
  speech/Speaker.kt        // TextToSpeech wrapper
  speech/Listener.kt       // SpeechRecognizer wrapper
  util/ImageUtils.kt       // rotate, resize, JPEG, Base64
  util/Haptics.kt
  data/SettingsRepository.kt  // DataStore
```

### 14. State machine

`Idle → Capturing → Thinking → Speaking → Idle`, plus `Listening` (Ask mode only) and `Error → Idle`.

- Any trigger while not Idle means **Stop**: cancel the coroutine Job, stop TTS, stop the recognizer, and return to Idle with one short vibration.
- Log each request's total time (capture → speech start) with `Log.d("VisionAgent", ...)` so I can measure latency for my report.

### 15. Deliverables

1. Every source file in full, plus `build.gradle.kts` (project and app), `libs.versions.toml`, `AndroidManifest.xml`, and `.gitignore`.
2. Step-by-step instructions for:
   - creating the project in Android Studio
   - adding the API key to `local.properties`
   - enabling USB debugging and running on my phone
3. A short test checklist:
   - Describe works indoors
   - Read Text reads a printed page and a medicine packet
   - Ask answers "Is there a chair in front of me?"
   - Stop interrupts speech
   - Airplane mode gives the no-internet message
   - A wrong API key gives the key message
   - Bangla mode speaks Bangla (if the voice is installed)
   - TalkBack reads every button

Build it step by step. First output the Gradle and Manifest files, then the code files one by one, then the instructions. If anything in these requirements conflicts with current Android or CameraX APIs, use the current correct API and add a one-line note explaining the change.

---

## After it works: ideas for version 2

- **Fast offline obstacle warnings:** add an on-device detector, such as a TensorFlow Lite / LiteRT YOLO or MediaPipe Object Detector. Keep it opt-in so the app stays silent unless a "Walk mode" is switched on.
- **Glasses later:** replace the phone camera with images arriving over Bluetooth from the ESP32-S3 glasses. The Gemini, speech and button logic stays the same.
- **Privacy note for your report:** on Google's free tier, submitted content may be used by Google to improve its products. Mention this, and avoid photographing private documents or other people's faces during testing. A paid tier or an on-device model avoids this.
