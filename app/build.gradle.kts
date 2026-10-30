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
        versionCode = 1
        versionName = (findProperty("tmVersionName") as String?) ?: "0.1.0"

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
            ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
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
