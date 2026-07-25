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

    // Instrumented tests target the MINIFIED `release` variant so JacksonR8SmokeTest
    // validates the R8 keep-rules against the actually-shrunk app (ledger C-ZVMF).
    // Running it needs an emulator/device + a release signing config (CI/HIL gate);
    // it compiles here via `:app:assembleReleaseAndroidTest`.
    testBuildType = "release"

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
            // R8 rules for the minified instrumented-test APK (testBuildType=release).
            testProguardFiles("test-proguard-rules.pro")
            // Phase-0 placeholder: sign the minified release with the DEBUG keystore
            // so `connectedReleaseAndroidTest` (JacksonR8SmokeTest — the R8 runtime
            // gate) is installable on an emulator/CI. Replace with a real release
            // signing config before any store release.
            signingConfig = signingConfigs.getByName("debug")
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
