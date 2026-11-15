plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val preview = providers.gradleProperty("axisPreview").orNull
val previewLabel = when (preview) {
    "0.5.0" -> "Axis 0.5 Preview"
    "true" -> "Axis Preview"
    else -> null
}

android {
    namespace = "io.github.kittkanev.axis"
    compileSdk = 35

    defaultConfig {
        applicationId = when (preview) {
            "0.5.0" -> "io.github.kittkanev.axis.preview050"
            "true" -> "io.github.kittkanev.axis.preview"
            else -> "io.github.kittkanev.axis"
        }
        manifestPlaceholders["axisLabel"] = previewLabel ?: "Axis"
        manifestPlaceholders["axisAccessibilityLabel"] = previewLabel ?: "@string/device_control_label"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
