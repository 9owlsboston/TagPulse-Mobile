import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.openapi.generator)
}

// --- Generated backend client (AGENTS §2 hard rule) -------------------------
// The API models are GENERATED from the vendored TagPulse openapi.json — never
// hand-written. The vendored spec + its backend commit SHA live in
// ./contract/ (see contract/CONTRACT.md). Re-vendoring the spec is the ONLY
// supported way to change these models.
val openApiSpec = layout.projectDirectory.file("contract/openapi.json")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(openApiSpec.asFile.absolutePath)
    outputDir.set(generatedDir.get().asFile.absolutePath)
    modelPackage.set("com.tagpulse.gateway.core.api.model")
    apiPackage.set("com.tagpulse.gateway.core.api")
    packageName.set("com.tagpulse.gateway.core.api")
    // Generated Kotlin data classes with Jackson annotations (jackson-annotations
    // only — no databind/okhttp runtime pulled in; footprint budget). M0 needs
    // models only; the HTTP client + serialization runtime land at M4.
    configOptions.set(
        mapOf(
            "serializationLibrary" to "jackson",
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
    // M0 scope note: the generator's selective `models=Name` filter is broken
    // against this OpenAPI **3.1** spec (it silently emits zero files — 3.1
    // support is still "in development" upstream), so we generate the full model
    // set (all 145 component schemas -> 148 files incl. inline enums). This is
    // still generated-not-hand-written (AGENTS §2) and is a superset of the MVE
    // ingest models (TagReadCreate {Identity, Location} for POST /tag-reads/batch).
    // Footprint mitigation is UNVERIFIED/aspirational: R8 would tree-shake the
    // unused models, but release isMinifyEnabled=false today so it does NOT strip
    // them yet — making that load-bearing (enable R8 + keep-rules, or trim the
    // spec) is tracked as ledger C-ZVMF before any release footprint acceptance.
    // See contract/CONTRACT.md.
    globalProperties.set(
        mapOf(
            "models" to "",
            "modelDocs" to "false",
            "modelTests" to "false",
        ),
    )
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    generateApiTests.set(false)
    generateModelTests.set(false)
}

android {
    namespace = "com.tagpulse.gateway.core"
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

    // Robolectric drives the Room outbox tests on the JVM (no emulator here):
    // it needs Android resources on the unit-test classpath.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        }
    }
}

// Ensure the client is generated before any Kotlin compilation.
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(tasks.named("openApiGenerate"))
}
// Lint also parses sources, so it needs the generated code present.
tasks.matching { it.name.startsWith("lint") }.configureEach {
    dependsOn(tasks.named("openApiGenerate"))
}

dependencies {
    // jackson-annotations (annotations only) compiles the generated models. The
    // Jackson serialization RUNTIME (databind + kotlin module) is pulled in EARLY
    // (ahead of the M4 HTTP client) because the M3 outbox genuinely needs JSON:
    // it serializes Observation.payload / location to the row's *_json columns and
    // reconstructs them on read. See contract/CONTRACT.md.
    api(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    api(libs.kotlinx.coroutines.core)

    // Durable outbox store (plan §7): Room over SQLite, compiler via KSP.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // M4 relay: a THIN OkHttp transport over the GENERATED models (AGENTS §2 —
    // the hard rule is about models, which stay generated; a thin transport that
    // serializes the generated TagReadCreate is acceptable, see contract/CONTRACT.md
    // "Transport decision"). OkHttp is the northbound HTTPS client (plan §3/§7).
    implementation(libs.okhttp)
    // M4 credential store: EncryptedSharedPreferences (Android Keystore-backed) so
    // the ingest API key + device_id live in the platform secure store, never in
    // source/resource files/logs (AGENTS §2). Compile-only for unit tests; the real
    // AndroidKeyStore path is a HIL check (see KeystoreCredentialStore).
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    // Room runs on the JVM under Robolectric — the faithful analogue of the A4
    // instrumented "enqueue → kill → relaunch → still pending" restart test.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // M4 BackendClient tests drive the real OkHttp client against a loopback
    // MockWebServer (no network, no device) — asserts path/method/auth header/body
    // shape + response parsing + status→outcome mapping.
    testImplementation(libs.okhttp.mockwebserver)
}
