plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 moves the Compose compiler into a Gradle plugin (replaces the old
    // kotlinCompilerExtensionVersion) — the M5 "Scan vehicle" UI is Jetpack Compose.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tagpulse.mobile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.tagpulse.mobile"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumented tests target the MINIFIED `r8Test` variant so JacksonR8SmokeTest
    // validates the R8 keep-rules against an app shrunk exactly like `release`
    // (ledger C-ZVMF), without adding test-only keeps to the shipped `release` app.
    // Running it needs an emulator/device + a release signing config (CI/HIL gate);
    // it compiles here via `:app:assembleR8TestAndroidTest`.
    testBuildType = "r8Test"

    buildTypes {
        release {
            // Footprint (ledger C-ZVMF): R8 makes the generated-model tree-shaking
            // load-bearing — the ~145-schema OpenAPI superset is shrunk to the used
            // ingest models. Keep-rules for the reflective Jackson stack ship as
            // :gateway-core consumer rules (see gateway-core/consumer-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Phase-0 placeholder: sign the minified release with the DEBUG keystore
            // so the `r8Test` variant (JacksonR8SmokeTest — the R8 runtime gate) is
            // installable on an emulator/CI. Replace with a real release signing
            // config before any store release.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Dedicated minified variant for the instrumented R8 gate (testBuildType).
        // It `initWith(release)` so it inherits the EXACT release R8 config (minify +
        // the :gateway-core consumer-rules that drive the C-ZVMF model shrinking) —
        // JacksonR8SmokeTest therefore validates the same R8 behavior as `release` —
        // but adds test-harness/cross-APK keeps (proguard-rules-r8test.pro) that the
        // shipped `release` app must NOT carry. Keeping them here (not in `release`)
        // is why the production APK stays free of test-only keep surface.
        create("r8Test") {
            initWith(getByName("release"))
            proguardFiles("proguard-rules-r8test.pro")
            // R8 rules for the minified instrumented-test APK itself.
            testProguardFiles("test-proguard-rules.pro")
            // :gateway-core / :obdii have no `r8Test` type — resolve against release.
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The ScanCoordinator gate tests drive a real Robolectric Room-backed Outbox
    // (the same JVM-analogue seam :gateway-core uses) — needs Android resources on
    // the unit-test classpath.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":obdii"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.core)

    // Composition-root visibility: :gateway-core keeps Room / OkHttp / Jackson as
    // `implementation` (footprint encapsulation), but the app is where the concrete
    // stack is assembled (AppContainer) — it must see the supertypes of the public
    // factories/clients it constructs (OutboxDatabase : RoomDatabase; the
    // OkHttpBackendClient default OkHttpClient/ObjectMapper params). These are already
    // in the merged APK via :gateway-core — this only lifts them onto app's compile path.
    implementation(libs.room.runtime)
    implementation(libs.okhttp)
    implementation(libs.jackson.databind)

    // Jetpack Compose (BOM-aligned) — the "Scan vehicle" single-screen flow (M5).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Enrolment QR scanner (ledger C-RYH7 Increment 1b): CameraX preview/analysis +
    // ML Kit barcode-scanning (bundled). The camera glue is HIL; the pure payload
    // parser (EnrolmentQrCode) is gate-tested.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    // ScanCoordinator logic is the gate-covered part (the Compose screen + the
    // Android GPS/BLE/Keystore impls are HIL): Robolectric drives the real Room
    // Outbox, coroutines-test drives the suspend flow deterministically.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented (androidTest) smoke test — validates the R8 keep-rules (C-ZVMF)
    // against the minified release variant on an emulator/CI.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
