plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.carlink"
    compileSdk = 37

//###############################################
//###############################################
//###############################################

    defaultConfig {
        applicationId = "zeno.carlink"
        minSdk = 32 // GM gminfo = Android 12L / API 32
        targetSdk = 36
        versionCode = 147
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
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Sideload-only (cp-stripped): a single APK installed directly on the head unit, never
    // uploaded to Play. The cluster-icon ContentProvider authority machinery + the `play`
    // flavor were removed along with cluster navigation. The single flavor is retained so the
    // build variant stays `sideloadDebug`/`sideloadRelease`.
    flavorDimensions += "distribution"

    productFlavors {
        create("sideload") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation for debug checks
        aidl = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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
        // Media3 UnstableApi false positive. Tool disagreement, verified on AGP 9.1 / media3
        // 1.10.1: the Kotlin compiler treats UnstableApi as a non-opt-in marker (a @file:OptIn
        // would warn "has no effect", so it is intentionally absent), but lint's
        // experimental-annotation check still demands the opt-in and errors on every Media3
        // use in MediaSessionManager.kt / CarlinkMediaBrowserService.kt. The code is correct
        // and compiles clean; suppress the lint check.
        disable += "UnsafeOptInUsageError"
        // Baseline captures the remaining accepted warnings (UnusedResources, OldTargetApi,
        // etc.) so lintSideloadDebug passes and surfaces only NEW issues going forward.
        baseline = file("lint-baseline.xml")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DocumentFile — no longer used directly (the SAF log-export feature was removed), but
    // pulled in transitively via androidx legacy-support; pinned to a newer version for alignment.
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration — Media3 1.10.1.
    // media3-session supersedes legacy androidx.media:media (MediaSessionCompat, deprecated
    // in androidx.media 1.8.0-alpha01). GM AAOS observers use platform android.media.session.*
    // APIs which Media3 auto-registers under the hood for backwards compatibility, so the
    // GMCarMediaService → ClusterService → cluster pipeline keeps working.
    // media3-common provides Player / SimpleBasePlayer / MediaItem / MediaMetadata.
    // media3-exoplayer is intentionally NOT included: this app does not decode/play audio
    // locally — the connected phone plays over USB; we only mirror metadata + forward commands.
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

