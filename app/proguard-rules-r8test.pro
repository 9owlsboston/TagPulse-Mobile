# =============================================================================
# R8 keep-rules for the `r8Test` build type ONLY (testBuildType = "r8Test").
# -----------------------------------------------------------------------------
# `r8Test` is a minified variant that `initWith(release)` — it inherits the exact
# release R8 config (isMinifyEnabled + the :gateway-core consumer-rules that drive
# the C-ZVMF model tree-shaking), so the instrumented `JacksonR8SmokeTest` still
# validates the SAME R8 behavior as the shipped `release` app. These extra keeps
# exist solely to let the instrumented-test harness (a separate, also-minified test
# APK) load and invoke the app-under-test across the APK boundary. They are NOT
# applied to `release`, so the shipped app carries none of this test-only surface.
# =============================================================================

# Keep androidx.tracing.Trace in the minified *app-under-test* APK.
# `AndroidJUnitRunner.onCreate()` (from the instrumented test APK) calls
# androidx.tracing.Trace, but the test APK's R8 de-dupes it expecting the
# app-under-test to provide it — while the app's own (Compose) use of tracing is
# optimized away. Without this keep the class ends up in *neither* APK and the
# instrumentation process crashes at startup with
# `NoClassDefFoundError: androidx.tracing.Trace` (ledger C-ZVMF R8 smoke gate).
-keep class androidx.tracing.Trace { *; }

# --- Instrumented-test harness support (C-ZVMF R8 smoke gate) ---
# The minified app-under-test inlines its own kotlin-stdlib usage away, so the
# app APK defines almost no kotlin.* classes. The instrumented test APK is R8'd
# with the app's classes as a PROVIDED input (de-dup), so it cannot package
# kotlin-stdlib either — the AndroidJUnitRunner harness then fails at runtime with
# NoClassDefFoundError on the kotlin-stdlib facades it calls. Keep the minimal set
# the harness references (R8 pulls their transitive closure) so the merged
# instrumentation process resolves them. Test-only; negligible size, and it does
# NOT retain the generated OpenAPI models the C-ZVMF footprint metric tracks.
-keep class kotlin.collections.MapsKt { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.ContinuationKt { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt { *; }
-keep class kotlin.coroutines.jvm.internal.SuspendFunction { *; }
-keep class kotlin.io.CloseableKt { *; }
-keep class kotlin.jvm.internal.SourceDebugExtension { *; }
-keep class kotlin.jvm.internal.StringCompanionObject { *; }
-keep class kotlin.jvm.JvmName { *; }
-keep class kotlin.jvm.JvmStatic { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Companion { *; }
-keep class kotlin.ResultKt { *; }
-keep class kotlin.time.Duration$Companion { *; }
-keep class kotlin.time.DurationKt { *; }
-keep class kotlin.TuplesKt { *; }
# The smoke test's own assertions call kotlin-stdlib String helpers (e.g.
# json.contains(...) -> kotlin.text.StringsKt.contains$default). The app's StringsKt
# is present but shrunk to the overloads the app uses, so keep its members too
# (de-dup routes the test's calls to the app-under-test's StringsKt).
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.collections.CollectionsKt { *; }

# The instrumented C-ZVMF smoke test (a separate APK) invokes these serialization
# entry points from the app-under-test by their original names, so R8 must not
# rename/inline them or the cross-APK call fails (NoSuchFieldError on the removed
# Companion / NoClassDefFoundError on the renamed class). These are already public
# gateway-core APIs used in production; pinning two façade classes by name is a
# minimal deopt and does NOT retain the generated OpenAPI models (still renamable
# via -keepclassmembers), so the C-ZVMF footprint metric is unchanged.
-keep class com.tagpulse.gateway.core.relay.OkHttpBackendClient { *; }
-keep class com.tagpulse.gateway.core.relay.OkHttpBackendClient$Companion { *; }
-keep class com.tagpulse.gateway.core.outbox.OutboxJson { *; }
