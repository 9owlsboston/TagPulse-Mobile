import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    // set. This is still generated-not-hand-written (AGENTS §2) and is a superset
    // of the MVE ingest models (TagReadCreate {Identity, Location} for
    // POST /tag-reads/batch); R8 tree-shakes unused models out of release builds,
    // holding the footprint line. See contract/CONTRACT.md.
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
    // jackson-annotations (annotations only) compiles the generated models; the
    // serialization runtime (databind) is deferred to the M4 HTTP client.
    api(libs.jackson.annotations)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
