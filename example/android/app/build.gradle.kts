plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.webex_calling_example"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.webex_calling_example"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = maxOf(flutter.minSdkVersion, 28)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    val useDynamicDelivery =
        gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    if (useDynamicDelivery) {
        dynamicFeatures += setOf(":webex_calling_feature")
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            pickFirsts +=
                setOf(
                    "META-INF/androidx.lifecycle_lifecycle-runtime.version",
                    "META-INF/androidx.appcompat_appcompat.version",
                    "META-INF/androidx.*.version",
                )
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("androidx.emoji2:emoji2:1.5.0")

    val useDynamicDelivery =
        gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    if (!useDynamicDelivery) {
        implementation(project(":webex_calling_sdk"))
    }
}
