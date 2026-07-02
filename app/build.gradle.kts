// app/build.gradle.kts  (MODULE level)
// -------------------------------------------------------------------
// This is where the actual app is configured: its package id, SDK
// versions, build features (ViewBinding), dependencies, and how to
// sign a release APK.
// -------------------------------------------------------------------

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The package name used to look up generated resources (R class)
    // and BuildConfig. Must match the manifest's applicationId family.
    namespace = "no.ambulanse.odysseus"

    // The Android API level we compile against. 34 = Android 14.
    compileSdk = 34

    defaultConfig {
        // The unique id of the app on the device and on the Play Store.
        applicationId = "no.ambulanse.odysseus"

        // Min SDK 24 = Android 7.0. The app will run on anything newer.
        minSdk = 24
        targetSdk = 34

        versionCode = 1
        versionName = "1.0"
    }

    // ----------------------------------------------------------------
    //  HOW TO BUILD A SIGNED RELEASE APK (no Play Store needed)
    // ----------------------------------------------------------------
    //  Android refuses to install an unsigned release APK. For private
    //  / direct installation ("sideloading") you sign it yourself.
    //
    //  STEP 1 — Generate a keystore (do this ONCE, keep it safe!):
    //      keytool -genkeypair -v \
    //          -keystore odysseus-release.jks \
    //          -keyalg RSA -keysize 2048 -validity 10000 \
    //          -alias odysseus
    //    It will ask for a keystore password, a key password, and
    //    your name/organisation. Remember these passwords — losing
    //    them means you can never update the app with the same key.
    //
    //  STEP 2 — Tell Gradle about the keystore. The block below reads
    //    the passwords from gradle.properties (or environment vars) so
    //    they are NOT hard-coded in this file. Add these lines to your
    //    ~/.gradle/gradle.properties (NOT committed to git):
    //
    //      ODYSSEUS_STORE_FILE=/absolute/path/odysseus-release.jks
    //      ODYSSEUS_STORE_PASSWORD=yourStorePassword
    //      ODYSSEUS_KEY_ALIAS=odysseus
    //      ODYSSEUS_KEY_PASSWORD=yourKeyPassword
    //
    //  STEP 3 — Build the signed APK:
    //      ./gradlew assembleRelease
    //    The finished, installable file appears at:
    //      app/build/outputs/apk/release/app-release.apk
    //
    //  STEP 4 — Copy it to your phone and tap it to install. You must
    //    allow "Install unknown apps" for your file manager once.
    // ----------------------------------------------------------------
    signingConfigs {
        create("release") {
            // Only configure signing if the properties exist, so that
            // a normal debug build still works without a keystore.
            val storeFilePath = (project.findProperty("ODYSSEUS_STORE_FILE") as String?)
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("ODYSSEUS_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("ODYSSEUS_KEY_ALIAS") as String?
                keyPassword = project.findProperty("ODYSSEUS_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // Shrink/obfuscate is off to keep this prototype simple.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release signing config defined above (when set up).
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Java/Kotlin language level.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Turn ON ViewBinding so we get type-safe access to views without
    // calling findViewById everywhere.
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core AndroidX + AppCompat for backwards-compatible UI.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material components (colors, themes, widgets).
    implementation("com.google.android.material:material:1.12.0")

    // Pull-to-refresh widget (SwipeRefreshLayout).
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Simple flat layouts.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lets us use the modern OnBackPressedCallback API.
    implementation("androidx.activity:activity-ktx:1.9.1")

    // SSH client for the built-in terminal. This is the maintained
    // fork of JSch (drop-in, same com.jcraft.jsch package) with modern
    // crypto/host-key algorithms needed to reach current OpenSSH servers.
    implementation("com.github.mwiede:jsch:0.2.17")
}
