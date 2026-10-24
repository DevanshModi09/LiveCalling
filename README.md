# LiveCalling — in-app audio calls with live voice checks

Two-person internet audio calls with room signaling, mute, hang-up, automatic echo cancellation, and a remote-audio hook for Voice Check. Independent of `android-app/`, `devansh/`, the existing PCM WebSocket relay, and detector weights. No current app screens or model are changed by this folder.

## Streaming requirements

The model slot remains empty. A model-neutral streaming engine now provides 500 ms audio-clock updates, explicit evidence states, bounded/skip-while-busy inference scheduling, timestamped 0.5 s bins, localisation and EMA display smoothing. See [STREAMING_REQUIREMENTS.md](STREAMING_REQUIREMENTS.md) for adapter interfaces, acceptance gates and honest validation status. [evaluation/](evaluation/README.md) builds and verifies at least 100 partial-fake audio cases plus codec, noise, 8 kHz and shortcut-control variants. These tools do not substitute for validating a replacement model.

## Teammate handoff

Start with [INTEGRATION.md](INTEGRATION.md) to add the reusable calling Activity to an existing Android app. The sample APK is optional.

## Installable calling APK

A separate app is now included in `android/app/` (package `com.vcd.livecalling`, launcher name **Voice Check Calls**). It keeps the Voice Check warm background, orange controls, dial and typography. It does not modify the detector APK in `devansh/`.

Build from the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./android/gradlew -p android :app:assembleDebug --no-daemon
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Open **Voice Check Calls**, enter your deployed HTTPS calling-server address (or paste a full room invitation), grant microphone permission, and open the calling room. Create/share a room and tap Join. The other person can join through the browser link or paste it into the APK. Android's Share menu can also send a text invitation into this app. The server address is remembered. Returning to server settings or leaving the screen ends the call. No model is bundled or invoked.

The calling page is hosted by `server/`; deploy the current `web/` files along with it. A publicly reachable HTTPS server and suitable TURN configuration are still required for cross-network calls. This APK is a foreground WebView-based calling app, with no incoming ringing/push notification or background-call service yet.

## Contents

- `server/`: Node WebSocket signaling plus static hosting. Exchanges SDP and ICE; does not relay audio itself.
- `web/`: browser WebRTC calling UI. Media uses WebRTC (direct connection, or TURN relay when configured).
- `android/`: compilable Android library wrapping the same UI in a WebView, with microphone permission handling and a 16 kHz remote PCM bridge. This is a WebView integration, not a native `org.webrtc` SDK implementation.

## Run locally

```sh
cd server
npm ci
npm test
npm start
```

Open `http://localhost:8788` in two browser tabs, grant microphone access, generate a room code, then use **Share room link**. Opening the invitation pre-fills the room; each participant taps **Join call** and grants microphone access. Browser sharing uses the share sheet when available, with clipboard/manual-copy fallbacks; the Android wrapper opens the native share sheet. Room URLs are served correctly with their query parameters. Use headphones to prevent two tabs/devices feeding back. Each room admits two participants; after either leaves, both must join again. Microphone capture requires HTTPS except on localhost.

For two phones, expose this server through your own HTTPS reverse proxy, forwarding WebSocket upgrades. Plain `http://192.168...` will not provide microphone access. Set `ALLOWED_ORIGIN` to the exact public origin (e.g. `https://calls.example.com`). The WebView wrapper requires HTTPS and rejects navigation to other origins. Keep the calling origin dedicated: the Android bridge trusts scripts hosted there.

## Different networks: STUN/TURN

Without ICE servers, the local demo may connect on the same network but is not a reliable internet calling service. Supply your provider's STUN and TURN configuration:

```sh
ALLOWED_ORIGIN=https://calls.example.com \
ICE_SERVERS_JSON='[{"urls":["stun:YOUR_STUN_HOST:3478"]},{"urls":["turn:YOUR_TURN_HOST:3478?transport=udp","turns:YOUR_TURN_HOST:5349?transport=tcp"],"username":"YOUR_USERNAME","credential":"YOUR_PASSWORD"}]' \
npm start
```

`/config` supplies these values to clients, so use temporary scoped TURN credentials in a deployed service. TURN carries encrypted media when a direct connection fails. Room codes are bearer invitations, not user accounts: this demo has no identity verification, push notifications, rate limiting, or background-call service. Add those before public deployment. Keep calls in the foreground in this version.

## Android integration

1. Copy `android/src/main/java/com/vcd/calling/WebRtcCallView.kt` into your app, or include `android/` as a Gradle library module.
2. Include the permissions from `android/src/main/AndroidManifest.xml`. Request `RECORD_AUDIO` at runtime **before** constructing `WebRtcCallView`.
3. Load the HTTPS origin serving `web/` and `server/`. Keep Android System WebView updated.
4. Attach `call.view` to your Activity or a Compose `AndroidView`. Call `call.close()` on the UI thread when leaving the call screen; recreate it on returning. Activity recreation ends this foreground-only call.

Activity example (after permission grant):

```kotlin
val pendingPcm = kotlinx.coroutines.channels.Channel<FloatArray>(
    capacity = 100,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
)
val call = com.vcd.calling.WebRtcCallView(
    activity = this,
    serverUrl = "https://calls.example.com/",
    onRemotePcm16k = { samples -> pendingPcm.trySend(samples) },
    onState = { state -> /* update your call state */ },
)
setContentView(call.view)
// Optional future integration: collect pendingPcm on a background worker.
// Model processing is intentionally left unconnected.
// Close the channel/worker AND call.close() when the call screen is disposed.
```

For the existing `PcmSource` abstraction, expose `pendingPcm.receiveAsFlow()` from `frames()` and start that source when the call connects. Never start `MicSource` alongside WebRTC. WebRTC owns the microphone; the detector must receive the **other participant's** audio.

Build the standalone wrapper using the included Gradle wrapper:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./android/gradlew -p android assembleDebug --no-daemon
```

## Detector audio contract

The remote WebRTC track plays through an `<audio>` element. A separate Web Audio branch requests a 16 kHz AudioContext, downmixes the received track to mono, and delivers 320 float samples every 20 ms. The detector branch outputs silence to avoid duplicate playback. The browser resamples before the worklet. If a browser cannot provide the requested context, calling can continue but detector input reports unavailable; there is no fallback to scoring the local microphone.

Browser integration:

```js
window.addEventListener('voicecheck-remote-pcm', ({ detail }) => {
  // detail.samples: Float32Array, mono; detail.sampleRate: 16000
  // Enqueue into a bounded worker queue; do not block the audio/UI thread.
});
```

Android receives equivalent little-endian PCM16 over a JavaScript bridge and converts it back to floats. This adds IPC overhead and PCM16 rounding. It is suitable for an integration prototype; measure latency on actual phones. Live inference can skip work when busy using the existing streaming scheduler. Calls do not require a model; detection does require wiring the provided hook into the app's detector.

## Verification and limits

Verified locally: all six Node tests pass, JavaScript syntax checks pass, and the Android library builds with `assembleDebug`. The tests cover PCM continuity/downmixing plus room admission, offer/answer and ICE forwarding, room-full rejection, peer departure, and origin rejection. The Android wrapper can be built independently. Two-device audible calling, microphone routing, network switching, and detector inference during a call require a real-device test; they are not established by server tests or a library build.

References: [WebRTC peer connections](https://webrtc.org/getting-started/peer-connections), [WebRTC TURN](https://webrtc.org/getting-started/turn-server), [Android WebView microphone permission requests](https://developer.android.com/reference/android/webkit/PermissionRequest).

## Planned full app integration

This folder is the workspace for WhatsApp-style in-app audio calling with live remote-voice analysis. The current prototype provides room calls and a PCM hook. Remaining work includes a fully native call UI, user identity, incoming-call notifications, foreground call lifecycle and audio routing, authenticated signaling and TURN credentials, and connecting the PCM stream to the detector with measured latency. These features are planned, not implemented by the folder rename.

## Model integration — intentionally empty

No model, inference runtime, thresholds, or verdict logic is bundled or selected in LiveCalling. Calls work independently of model integration. The remote-audio hook remains available for a future model; until one is connected, the app does not perform voice-authenticity checks or produce detection results.

The hook currently supplies 16 kHz mono audio in 20 ms chunks. Choose window length, preprocessing, inference runtime, thresholds, and update cadence only after the replacement model's contract is known. Adapt or resample this audio if that model requires a different format.
