# Give this folder to the Android teammate

This is a reusable calling feature. It contains the Android screen, WebView WebRTC integration, room-link sharing, JavaScript call client, remote PCM callback, Node signaling server, and a demo APK project. It contains no detector, model weights, thresholds, or inference runtime.

**Your teammate needs their Android app's source project to merge this feature and rebuild their APK. An already compiled APK alone is not an integration project.** Their existing detector can stay as it is.

## 1. Add the Android library

Copy the whole `LiveCalling/` folder to the root of their Android project. Add to their root `settings.gradle.kts`:

```kotlin
include(":liveCalling")
project(":liveCalling").projectDir = file("LiveCalling/android")
```

The nested `LiveCalling/android/settings.gradle.kts` is for standalone builds only and is not used when included this way. The demo `android/app/` does not need to be included in their app.

Replace `LiveCalling/android/build.gradle.kts` with this version when integrating into a host project that already manages Android plugin versions:

```kotlin
plugins { id("com.android.library") }
android {
    namespace = "com.vcd.calling"
    compileSdk = 37 // match the host's installed SDK; current source uses minSdk 26 APIs
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

Ensure `com.android.library` is declared using the **same Android Gradle Plugin version as their host project**, and Kotlin compilation is enabled. AGP 9 has built-in Kotlin; AGP 8 projects need their existing Kotlin Android plugin applied to this library too. This standalone project was built with AGP 9.4.1 and SDK 37; different toolchains must be verified in the host project.

In their app module:

```kotlin
dependencies { implementation(project(":liveCalling")) }
```

The library manifest merges INTERNET, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, and its internal Activity. No new model dependency is added.

## 2. Open the calling screen

Wire the existing app's Call button to:

```kotlin
startActivity(
    android.content.Intent(this, com.vcd.calling.LiveCallingActivity::class.java)
        .putExtra("server_url", "https://call.YOUR_DOMAIN.com/")
)
```

Or prefill an invitation:

```kotlin
startActivity(
    android.content.Intent(this, com.vcd.calling.LiveCallingActivity::class.java)
        .putExtra("room_url", "https://call.YOUR_DOMAIN.com/?room=SHARED_ROOM_CODE")
)
```

The screen requests microphone permission, remembers the server address, and opens the styled room UI. Users generate/share a room link, then both tap Join. Mute and End call work inside the room. Back or Server settings ends the call. Keep this version in the foreground. The host's model remains untouched.

If the host is Java-only, it can launch this Activity with an ordinary Intent; the library source still requires Kotlin compilation.

## 3. Run the service

Deploy `server/` AND `web/` together, preserving their sibling paths. The server loads files from `../web/`.

```sh
cd LiveCalling/server
npm ci
ALLOWED_ORIGIN=https://call.YOUR_DOMAIN.com \
ICE_SERVERS_JSON='[{"urls":["stun:YOUR_STUN:3478"]},{"urls":["turn:YOUR_TURN:3478?transport=udp"],"username":"USER","credential":"PASSWORD"}]' \
npm start
```

Use HTTPS with WebSocket proxying in front of port 8788. Keep Node running with a service manager. Configure a TURN service and its firewall for cross-network calling. See `deploy/` for reverse-proxy and service examples. These files do not provision AWS or supply a domain/certificate/TURN account. Never put permanent provider secrets into source control; production TURN credentials should be scoped and temporary.

Without a reachable HTTPS service the app cannot call. Room URLs open in a browser by default; paste/share the invitation into the app to use its calling screen. Automatic HTTPS app links need a real domain, manifest filters, and domain association, which are not configured here.

## 4. Model hook — leave empty for now

The complete call feature works without a model. The reusable `WebRtcCallView` exposes `onRemotePcm16k: (FloatArray) -> Unit`. It emits the other participant's mono 16 kHz audio in 320-sample chunks. `LiveCallingActivity` deliberately leaves that callback empty.

When a model is chosen, enqueue the samples to a bounded background pipeline. Do not run inference on the JavaScript bridge thread. Adapt the sample rate and buffering to the chosen model. Do not score the local microphone or run a second microphone recorder alongside WebRTC.

## What this handoff does and does not include

Included: foreground room-based internet audio calling, native permission screen, matching design, share-link flow, offer/answer and ICE exchange, two-participant limits, remote PCM hook, cleanup, tests, and a demo launcher.

Still requires deployment/device validation: HTTPS/TURN hosting, two real phones on different networks, microphone/speaker behavior, background/lock-screen behavior. Push ringing, user accounts, contact lists, automatic app links, reconnect, and a background call service are not implemented. No actual call audio or future detector accuracy has been validated by server tests.
