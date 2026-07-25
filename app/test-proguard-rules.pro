# R8 rules for the instrumented (androidTest) APK only (applied via
# `testProguardFiles` — the test APK is minified because instrumented tests target
# the `release` build type, testBuildType="release", to validate the app's R8
# keep-rules; ledger C-ZVMF).
#
# androidx.test references errorprone build-time annotations absent at runtime.
-dontwarn com.google.errorprone.annotations.**
