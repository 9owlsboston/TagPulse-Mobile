# =============================================================================
# Footprint R8 keep-rules (ledger C-ZVMF)
# -----------------------------------------------------------------------------
# CONSUMER rules: applied automatically whenever a consuming app (:app) enables
# R8 (isMinifyEnabled=true). They let R8 tree-shake the ~145-schema generated
# OpenAPI-model SUPERSET down to the models the app actually references, while
# keeping Jackson's reflective (de)serialization of the REACHED models working.
# Debug builds don't run R8 and are unaffected.
# =============================================================================

# Jackson + jackson-module-kotlin read these attributes reflectively at runtime.
-keepattributes Signature,InnerClasses,EnumConstants,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,AnnotationDefault,*Annotation*

# Generated OpenAPI models: keep the MEMBERS (constructors, fields, and the
# `@get:JsonProperty` GETTERS the generator emits) of any model class R8 KEEPS as
# reachable, so Jackson can bind them. Using `-keepclassmembers` (NOT `-keep`) is
# deliberate -- it does NOT pin the classes, so R8 still strips the ~140 models the
# app never references (the whole point of C-ZVMF: tree-shake the superset down to
# the used ingest models, TagReadCreate {Identity, Location}).
-keepclassmembers class com.tagpulse.gateway.core.api.model.** {
    <init>(...);
    <fields>;
    <methods>;
}

# GeoLocation is (de)serialized reflectively by the outbox JSON codec (OutboxJson
# encode/decodeLocation) -- it lives outside the api.model package. It must be kept
# FULLY (not just -keepclassmembers): jackson-module-kotlin discovers the primary
# constructor via the class's Kotlin @Metadata, which R8 full-mode does not keep
# consistent across a rename -- a renamed GeoLocation deserializes as "no Creators,
# cannot construct instance" (caught by the C-ZVMF instrumented smoke test on the
# minified release variant; the unminified JVM contract test never saw it).
-keep class com.tagpulse.gateway.core.GeoLocation { *; }

# jackson-module-kotlin resolves data-class constructor params via Kotlin @Metadata.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }

# Anonymous `TypeReference` subclasses (OkHttpBackendClient INT_MAP/ANY_MAP and
# OutboxJson's payload-map read) carry their generic parameterization only in their
# class Signature. `-keep` (NOT `-keepclassmembers`) is required: under R8 full-mode,
# keeping only members lets R8 erase the subclass's generic-superclass Signature, so
# `new TypeReference(){}` throws "constructed without actual type information" at
# class-init -- which breaks OkHttpBackendClient.<clinit> in the minified release
# app (caught by the C-ZVMF instrumented smoke test; invisible to the unminified
# JVM contract test).
-keep class com.fasterxml.jackson.core.type.TypeReference
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }

# jackson-databind references JDK/Bean classes absent on Android -- silence the
# R8 full-mode missing-class errors (build-fatal otherwise). Reconciled against
# R8's missing_rules.txt at build time.
-dontwarn java.beans.**
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn org.w3c.dom.**

# Tink (via androidx.security:security-crypto, used by KeystoreCredentialStore)
# references errorprone build-time annotations absent at runtime on Android.
-dontwarn com.google.errorprone.annotations.**
