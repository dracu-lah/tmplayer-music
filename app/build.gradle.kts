import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Release signing, only when keystore.properties (gitignored) exists. Debug builds use the
// machine's own debug key, so nothing secret is needed to build and install one.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// A build for a store, switched on with:
//
//     ./gradlew :app:assembleRelease -PtmStore=true
//
// A store installs and updates the app itself, so that build never asks GitHub for a newer release
// and draws nothing about one (BuildConfig.UPDATE_CHECK, read into Updates.enabled), and its
// manifest leaves REQUEST_INSTALL_PACKAGES out (src/store/AndroidManifest.xml). Off, which is every
// other build and the GitHub APK the release workflow makes, the updater is in. Adapted from
// TMPlayer's tmFdroid switch; there is no store flavor yet, so it is a property.
val storeBuild: Boolean = (findProperty("tmStore") as String?)?.trim()?.toBoolean() ?: false

android {
    namespace = "com.tmplayer.music"
    // 37 because tdl-coroutines refuses to be compiled against anything older (as TMPlayer).
    compileSdk = 37

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.tmplayer.music"
        // Android 6, TMPlayer's floor. Newer APIs are guarded where they are called.
        minSdk = 23
        targetSdk = 35
        // The release workflow passes both from the tag (major*1000000 + minor*10000 + patch*100 +
        // stage); a local build is code 1, which any release installs over.
        versionCode = (findProperty("tmVersionCode") as String?)?.trim()?.toIntOrNull() ?: 1
        versionName = (findProperty("tmVersionName") as String?) ?: "0.1.0"

        // The self-updater of the GitHub APK; off in a store build (see storeBuild above).
        buildConfigField("boolean", "UPDATE_CHECK", "${!storeBuild}")

        // The app's own Telegram API credentials, from local.properties or the environment. A
        // build without them compiles and says so on the sign in screen.
        val apiId = localProps.getProperty("TG_API_ID") ?: System.getenv("TG_API_ID") ?: "0"
        val apiHash = localProps.getProperty("TG_API_HASH") ?: System.getenv("TG_API_HASH") ?: ""
        buildConfigField("int", "TG_API_ID", apiId.toIntOrNull()?.toString() ?: "0")
        buildConfigField("String", "TG_API_HASH", "\"$apiHash\"")

        androidResources.localeFilters += listOf("en")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the signed app, so a test build never asks to wipe the real install.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // x86_64 for emulators, Chromebooks and x86 TV boxes: every native lib (TDLib, FFmpeg,
            // sqlite, graphics-path) ships it. x86 also ships but is left out (32-bit x86 devices are rare).
            ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The store build's manifest overlay, which takes the install permissions back out.
    if (storeBuild) {
        sourceSets.getByName("debug").manifest.srcFile("src/store/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/store/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // @Preview for the theme and player components; the renderer itself only in debug builds.
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tdl.coroutines)
    implementation(libs.zxing.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    // FFmpeg audio decoders for what the phone's own codecs lack (FLAC and Opus before Android 8, ALAC).
    implementation(libs.nextlib.media3ext)

    testImplementation(libs.junit)
}
