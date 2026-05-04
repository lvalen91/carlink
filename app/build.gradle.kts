plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.carlink"
    compileSdk = 36

//###############################################
//###############################################
//###############################################

    defaultConfig {
        applicationId = "zeno.carlink"
        minSdk = 32
        targetSdk = 36
        versionCode = 130
        versionName = "1.0.0"

//###############################################
//###############################################
//###############################################

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Distribution split:
    //   sideload → APK only (Play Console rejects apps that claim Templates Host's
    //              ClusterIconContentProvider authority globally). Shim claims the
    //              GM authority so Templates Host icon delivery works on-device.
    //   play     → AAB upload to Play Store. Shim is registered under an
    //              applicationId-suffixed authority to avoid the Play Console
    //              "content provider authority in use by other developers" rejection
    //              (issue #6). Templates Host's calls to the GM authority go nowhere
    //              on Play installs; NavigationStateManager.initialize() detects this
    //              at runtime and falls back to type-based maneuver icons.
    flavorDimensions += "distribution"

    productFlavors {
        create("sideload") {
            dimension = "distribution"
            manifestPlaceholders["clusterIconAuthority"] =
                "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"
            buildConfigField(
                "String",
                "CLUSTER_ICON_AUTHORITY",
                "\"com.google.android.apps.automotive.templates.host.ClusterIconContentProvider\""
            )
        }
        create("play") {
            dimension = "distribution"
            manifestPlaceholders["clusterIconAuthority"] =
                "zeno.carlink.ClusterIconContentProvider"
            buildConfigField(
                "String",
                "CLUSTER_ICON_AUTHORITY",
                "\"zeno.carlink.ClusterIconContentProvider\""
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation for debug checks
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Suppress DiscouragedApi warning for scheduleAtFixedRate usage.
        // Tested alternatives (coroutines, scheduleWithFixedDelay) caused issues
        // with microphone timing - Timer.scheduleAtFixedRate works reliably.
        // See documents/revisions.txt [19], [21] for history.
        disable += "DiscouragedApi"
        disable += "Instantiatable"  // CarAppActivity from app-automotive AAR — false positive
        disable += "InvalidUsesTagAttribute"  // "navigation" is valid for Car App Library nav apps
    }
}

// Drop playDebug — Play Console rejects debuggable bundles, and there is no
// reason to build/install/run a debug variant tied to the play flavor. Result:
// Build Variants panel and the Generate Signed Bundle / APK wizard show only
// sideloadDebug, sideloadRelease, playRelease.
androidComponents {
    beforeVariants(selector().withName("playDebug")) { variant ->
        variant.enable = false
    }
}

// Kotlin compiler: report deprecations and unchecked casts as warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
        allWarningsAsErrors.set(false) // report but don't fail — tighten later
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true) // report only on first run — fix incrementally
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    ignoreFailures = true // report only on first run
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DocumentFile for SAF file operations (capture recording)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration — Media3 1.10.0 (latest stable, 2026-03-26).
    // media3-session supersedes legacy androidx.media:media (MediaSessionCompat, deprecated
    // in androidx.media 1.8.0-alpha01). GM AAOS observers use platform android.media.session.*
    // APIs which Media3 auto-registers under the hood for backwards compatibility, so the
    // GMCarMediaService → ClusterService → cluster pipeline keeps working.
    // media3-common provides Player / SimpleBasePlayer / MediaItem / MediaMetadata.
    // media3-exoplayer is intentionally NOT included: this app does not decode/play audio
    // locally — the connected phone plays over USB; we only mirror metadata + forward commands.
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Car App Library for AAOS cluster navigation (Templates Host)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-automotive:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

