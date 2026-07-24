plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tagpulse.gateway.obdii"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The driver implements the core's seam; it must NOT depend on HTTP / outbox.
    implementation(project(":gateway-core"))
    // BLE transport + ELM327 session are coroutine/Flow based.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // runTest + virtual-time dispatcher drive the session tests without hardware.
    testImplementation(libs.kotlinx.coroutines.test)
}
