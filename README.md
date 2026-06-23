# webex_calling

Flutter plugin for **Cisco Webex** using the **Full SDK** ([webex-android-sdk](https://github.com/webex/webex-android-sdk)) — calling, meetings, and messaging.

## Install from GitHub

Add to your app `pubspec.yaml`:

```yaml
dependencies:
  webex_calling:
    git:
      url: https://github.com/YOUR_GITHUB_USERNAME/webex_calling.git
      ref: main
```

Then run:

```bash
flutter pub get
cd ios && pod install && cd ..
```

## Host app setup (Android)

### 1. Maven repository

In `android/build.gradle.kts` (project root):

```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://devhub.cisco.com/artifactory/webexsdk/") }
    }
}
```

### 2. Include the Webex SDK module

In `android/settings.gradle.kts`, resolve the plugin path from Flutter (works for Git + pub cache):

```kotlin
fun readWebexCallingPluginPath(projectRoot: java.io.File): java.io.File {
    val depFile = projectRoot.resolve(".flutter-plugins-dependencies")
    check(depFile.exists()) {
        "Missing .flutter-plugins-dependencies. Run 'flutter pub get' in the app root."
    }
    val pattern =
        """"name"\s*:\s*"webex_calling".*?"path"\s*:\s*"([^"]+)""""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
    val path =
        pattern.find(depFile.readText())?.groupValues?.get(1)?.removeSuffix("/")
            ?: error("webex_calling not found in .flutter-plugins-dependencies")
    return java.io.File(path)
}

val webexCallingPluginPath = readWebexCallingPluginPath(file(".."))
include(":webex_calling_sdk")
project(":webex_calling_sdk").projectDir =
    webexCallingPluginPath.resolve("android/webex_calling_sdk")

// On-demand Webex SDK (App Bundle + Play installs only).
val useDynamicDelivery =
    gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
if (useDynamicDelivery) {
    include(":webex_calling_feature")
    project(":webex_calling_feature").projectDir =
        webexCallingPluginPath.resolve("android/webex_calling_feature")
}
```

### 3. App module (Play on-demand delivery)

In `android/app/build.gradle.kts`:

```kotlin
val useDynamicDelivery =
    gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

android {
    defaultConfig {
        minSdk = maxOf(flutter.minSdkVersion, 28)
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    if (useDynamicDelivery) {
        dynamicFeatures += setOf(":webex_calling_feature")
    }
}

dependencies {
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-common:2.2.20")

    // Embed SDK for flutter run / APK sideloads; on-demand module for App Bundle.
    if (!useDynamicDelivery) {
        implementation(project(":webex_calling_sdk"))
    }
}
```

### 4. SplitCompat + permissions

`MainActivity`:

```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    SplitCompat.install(this)
}
```

Add permissions to `android/app/src/main/AndroidManifest.xml` (see plugin manifest for the full list).

### Permissions (SDK 3.16+)

Your app must request runtime permissions before dial/join. See [Android Meetings SDK integration](https://developer.webex.com/meeting/docs/sdks/android-meetings-sdk-integrating-the-sdk).

## On-demand download (Play Store)

When you ship an **App Bundle** via Google Play, the Webex SDK (~200 MB) is in an **on-demand dynamic feature**. The base install stays smaller; users download Webex when your app calls:

```dart
await WebexCalling.instance.ensureModuleInstalled(
  onProgress: (state) => print('${state.status} ${state.progress}'),
);
```

| Install source | On-demand download |
|----------------|-------------------|
| Google Play (AAB) | Yes — `ensureModuleInstalled()` |
| APK sideload / `flutter run` | No — SDK embedded in base APK |

Build commands:

```bash
flutter build appbundle --release   # Play: on-demand module
flutter build apk --release         # Sideload: full SDK in APK
```

## Host app setup (iOS)

```ruby
platform :ios, '15.0'
```

The plugin depends on `WebexSDK` (~> 3.16.0), not `WebexSDK/Wxc`.

## Dart usage

```dart
import 'package:webex_calling/webex_calling.dart';

await WebexCalling.instance.ensureModuleInstalled(
  onProgress: (state) => print(state.status),
);

await WebexCalling.instance.initialize(
  WebexCallingConfig(
    clientId: 'YOUR_CLIENT_ID',
    clientSecret: 'YOUR_CLIENT_SECRET',
    redirectUri: 'YOUR_REDIRECT_URI',
    email: 'user@company.com',
  ),
);

// PSTN / Webex Calling (needs Phone services: Connected)
await WebexCalling.instance.dial('+18001234567');

// Meeting URL, SIP URI, or meeting number (Full SDK)
await WebexCalling.instance.joinMeeting(
  'https://company.webex.com/meet/abc123',
);
```

| API | Input | Requires |
|-----|-------|----------|
| `dial()` | E.164 phone number | Webex Calling + `Connected` phone services |
| `joinMeeting()` | Meeting URL / SIP / meeting number | OAuth sign-in |

## Architecture

```
Flutter (Dart)
    │
    ▼ MethodChannel
Base plugin (:webex_calling)
    ├── SplitInstallManager  → downloads :webex_calling_feature
    └── Reflection bridge    → WebexCallingEngine
                                    │
                                    ▼
Dynamic feature module (or embedded for debug)
    └── com.ciscowebex:webexsdk (Full SDK)
```

## Requirements

- OAuth integration at [developer.webex.com](https://developer.webex.com/)
- **Calling**: Webex Calling Professional license
- **Meetings**: Webex account with meeting entitlement
- SDK version: `3.16.x`

## Example

See the `example/` app in this repository.
