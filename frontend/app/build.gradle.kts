import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release signing is driven entirely by the environment, so the exact same
// build works three ways:
//   * nothing set            -> an UNSIGNED release APK (app-release-unsigned.apk)
//   * the 4 vars set (CI)    -> a SIGNED release APK
// The 4 names below are the 4 repository secrets the "generate-keystore"
// workflow produces: MIKASO_KEYSTORE_FILE (path to the .jks), MIKASO_STORE_PASSWORD,
// MIKASO_KEY_ALIAS and MIKASO_KEY_PASSWORD.
val releaseStoreFilePath = System.getenv("MIKASO_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("MIKASO_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("MIKASO_KEY_ALIAS")
val releaseKeyPassword = System.getenv("MIKASO_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mikaso.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mikaso.app"
        minSdk = 29          // Android 10+: saves to MediaStore without storage permissions
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Lets you talk to a local http:// backend (emulator 10.0.2.2 or LAN IP).
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            // Release builds only talk to https:// servers (e.g. your cPanel domain).
            manifestPlaceholders["usesCleartext"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when the 4 signing env vars are present (CI supplies them
            // from repo secrets). Otherwise this stays null and Gradle emits
            // app-release-unsigned.apk - which is exactly what we want by default.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        // "perf": exactly what a release build runs (R8 shrinking, no debug overhead) but still allows a local
        // http:// backend. Pick this in Build Variants when you want to feel the real speed of the app.
        create("perf") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            manifestPlaceholders["usesCleartext"] = "true"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)   // applies the baseline profiles that ship with Compose: faster start, smoother first frames
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)   // saving into a folder the user picked (SAF)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
