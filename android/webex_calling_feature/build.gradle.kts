plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.webex.flutter.calling.feature"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/*.version"
        }
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":webex_calling_sdk"))
}
