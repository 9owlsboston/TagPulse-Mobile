# R8 rules for the instrumented (androidTest) APK only (applied via
# `testProguardFiles` — the test APK is minified because instrumented tests target
# the minified `r8Test` build type (testBuildType="r8Test"), to validate the app's
# R8 keep-rules; ledger C-ZVMF).
#
# androidx.test references errorprone build-time annotations absent at runtime.
-dontwarn com.google.errorprone.annotations.**

# The test APK is minified (testBuildType="r8Test"), so R8 must not strip the
# instrumented-test harness — it is loaded reflectively by the Android
# instrumentation framework, not from any statically-reachable app call, so R8
# sees it as dead code and removes it. Without these keeps the reflective JUnit
# test discovery finds nothing on the release variant.
#
# (androidx.tracing.Trace, which AndroidJUnitRunner.onCreate() needs, is kept in
# the *app-under-test* proguard-rules.pro — the test APK's R8 de-dupes it to the
# app APK, so it must be retained there, not here.)
#
# 1) The instrumentation runner + androidx.test infra are instantiated by name.
#    Keep the whole androidx.test harness — it is reflectively loaded and its
#    (Kotlin) internals pull kotlin-stdlib facade classes (e.g. kotlin.LazyKt via
#    TestDirCalculator). Broad keeps are fine here: this is the *test* APK, which
#    is never shipped, so it does not affect the app-under-test footprint that the
#    C-ZVMF R8 gate protects.
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
-keep class kotlin.** { *; }
-dontwarn kotlin.**
# 2) JUnit runs test classes + @Test methods reflectively — keep them and their
#    @RunWith annotation so the release-variant test discovery still sees them.
-keepattributes *Annotation*
-keep @org.junit.runner.RunWith class * { *; }
-keepclasseswithmembers class * { @org.junit.Test <methods>; }
