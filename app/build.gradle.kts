plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version derived from git: deterministic per commit, monotonic on the single
// branch, zero ceremony. A shallow clone would silently yield count=1 and break
// sideload upgrades, hence the guard (CI checks out with fetch-depth: 0).
fun git(vararg args: String): String =
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()

val gitCount = git("rev-list", "--count", "HEAD").toInt()
    .also { require(!rootProject.file(".git/shallow").exists()) { "Shallow clone: git-derived versionCode would be wrong (use fetch-depth: 0)" } }
val gitSha = git("rev-parse", "--short", "HEAD")
val gitDate = git("log", "-1", "--format=%cd", "--date=format:%Y.%m.%d")

android {
    namespace = "com.newash.videocast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newash.videocast"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCount
        versionName = "$gitDate-r$gitCount ($gitSha)"

        val osApiKey = (project.findProperty("OPENSUBTITLES_API_KEY") as? String).orEmpty()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$osApiKey\"")
    }

    signingConfigs {
        getByName("debug") {
            // Pinned, committed keystore: each CI runner otherwise mints a fresh
            // random debug key, making every "update" a signature mismatch that
            // forces uninstall + data loss. A debug key confers no authority, so
            // committing it to a public repo is an accepted personal-app tradeoff.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    implementation("net.freeutils:jlhttp:3.2")
    implementation("com.github.albfernandez:juniversalchardet:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
