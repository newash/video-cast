plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.newash.videocast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newash.videocast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val osApiKey = (project.findProperty("OPENSUBTITLES_API_KEY") as? String).orEmpty()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$osApiKey\"")
    }

    // Release build intentionally unconfigured (no signing/minify): the CI debug
    // APK is the install channel for this personal app.

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
