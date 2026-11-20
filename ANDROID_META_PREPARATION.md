# ITGlasses Android and Meta Wearables preparation

## Recovered app baseline

- Phone package: `com.itglasses.itglassesandroid` (kept unchanged to preserve app data).
- Meta package: `com.itglasses.itglassesandroid.meta` (can be installed alongside phone build).
- Phone workflow: Android `AudioRecord` captures mono PCM16 at 16 kHz, the app sends WAV to the backend STT endpoint, sends recognized text to the unified assistant `/api/v1/assistant/chat` (manual RAG, conversation history, web search and spoken procedure narration), requests Piper audio, then plays it with Android `MediaPlayer`.
- Text and voice share one assistant `session_id`, so follow-ups such as "¿y el siguiente paso?" keep context. The session resets on disconnect or re-pairing.
- Phone input/output remain the only audio path. The DAT session below does not add camera, microphone, speech, or audio capabilities and does not change Android's audio route.
- The `phone` flavor supports Android API 24+ and does not package or depend on Meta DAT. The `meta` flavor supports API 29+ and includes the DAT SDK.

## Meta Wearables Device Access Toolkit

The `meta` flavor uses the official `com.meta.wearable:mwdat-core:1.0.1` artifact from Maven Central. It initializes the SDK, handles registration callbacks from both `onCreate` and `onNewIntent`, observes registration/device/session state, requests Android's Bluetooth Connect permission when needed, and only starts a session when DAT reports a connected device. The `phone` flavor excludes the DAT dependency and keeps its phone audio/backend workflow.

The manifest uses the official Developer Mode placeholders `APPLICATION_ID=0` and `CLIENT_TOKEN=0`; no Meta credentials or backend tokens are included. Register the app through the Meta AI app with Developer Mode enabled. A real release requires a registered Wearables Developer Center project and its official app configuration.

The UI reports the SDK's observed state and explicitly says when no device is connected. A DAT session only verifies the session lifecycle; it does not prove physical-hardware identity or provide glasses audio. The project does not include Mock Device Kit, camera capture, microphone streaming, or audio routing.

Official references:

- [Android integration guide](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/)
- [Requirements and glasses setup](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit/)
- [Session lifecycle](https://wearables.developer.meta.com/docs/develop/dat/lifecycle-events/)
- [Version dependencies](https://wearables.developer.meta.com/docs/develop/dat/version-dependencies/)

## Wi-Fi and Local Network Communication (Phase 1)

Debug builds default to `http://127.0.0.1:8000`, but now support connecting over the company Wi-Fi network without USB or ADB reverse.

1. **URL Configuration:**
   - Development builds accept loopback (`127.0.0.1`, `localhost`) and private LAN addresses (RFC 1918: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`; RFC 3927 link-local `169.254.0.0/16`; and local hostnames `.local`, `.internal`, `.test`).
   - Public IP addresses and public domains over unencrypted HTTP are strictly rejected.
   - The user can configure the Backend URL directly in the app UI (`et_backend_url`) or pass `-PITGLASSES_DEBUG_BACKEND_URL=http://<PC_LAN_IP>:8000` at build time.

2. **Bearer Authentication:**
   - When the backend binds to LAN or requires authentication (`ITGLASSES_REQUIRE_AUTH=true`), the app attaches `Authorization: Bearer <token>` to all protected API calls via `AuthInterceptor`.
   - The API Bearer token is entered securely via the app UI (`et_api_token`) and persisted in private app storage. No tokens are hardcoded in source, BuildConfig, or APK resources.
   - If authentication is rejected (HTTP 401), the app displays an actionable status message without leaking credentials.

## Debug backend over USB (Legacy fallback)

Both debug variants default to `http://127.0.0.1:8000`. Configure the backend URL at build time with `ITGLASSES_DEBUG_BACKEND_URL`; debug URLs must use a loopback host. Do not bind FastAPI to LAN. On this workstation, build with Android Studio's JDK and the existing SDK:

```powershell
Set-Location D:\ITGlasses-Android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = 'D:\Android Studio SDK\SDK'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat testPhoneDebugUnitTest testMetaDebugUnitTest assemblePhoneDebug assembleMetaDebug
```

For a USB-connected device, run the forwarding and install commands manually. The APKs use distinct application IDs and can coexist:

```powershell
adb devices
adb reverse tcp:8000 tcp:8000
adb reverse --list
adb install -r .\app\build\outputs\apk\phone\debug\app-phone-debug.apk
adb install -r .\app\build\outputs\apk\meta\debug\app-meta-debug.apk
```

`adb reverse` forwards the phone's loopback port to the PC's loopback port. Remove the forwarding manually when finished:

```powershell
adb reverse --remove tcp:8000
```

The app does not run ADB commands. When backend authentication is required, the app sends the user-configured Bearer token. Do not disable backend authentication or embed a permanent shared administrator token in the APK.

Release builds use a separate HTTPS-only URL and cleartext is disabled:

```powershell
.\gradlew.bat assemblePhoneRelease assembleMetaRelease -PITGLASSES_PRODUCTION_BACKEND_URL=https://your-approved-backend.example
```

Replace the example host with an operator-approved HTTPS endpoint. A release build without a production URL reports that the backend is not configured.

## First physical glasses test

1. Confirm the exact glasses model, firmware, Meta AI app version, and phone Android version against Meta's current [version dependencies](https://wearables.developer.meta.com/docs/develop/dat/version-dependencies/).
2. Pair and set up the glasses in Meta AI, then enable Developer Mode.
3. Install the `meta` debug APK manually and grant the requested Android Bluetooth permission.
4. In ITGlasses, choose **Register with Meta AI** and complete Meta AI's registration flow.
5. Confirm the UI reports the DAT-observed device state, then choose **Connect DAT session**. Confirm that start, pause, disconnect, and stop states appear without affecting phone audio.
6. Separately test the existing phone microphone and speaker workflow using USB reverse. This build does not use glasses microphone or speakers.

No physical glasses test has been performed. Registration/session state is not a hardware-compatibility result.
