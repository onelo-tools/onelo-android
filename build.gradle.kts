plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // vanniktech 0.34.0 (NOT 0.37.0): 0.34.0 is the newest release whose minimum is
    // AGP 8.0 / Kotlin 1.9.20 / Gradle 8.5 — compatible with this module's toolchain
    // (AGP 8.2.2, Kotlin 1.9.22, Gradle 8.6). 0.35+ requires AGP 8.13 / Gradle 8.13.
    // Central Portal has been supported since 0.28.0. (onelo-kotlin runs a newer
    // toolchain so it uses 0.37.0 — the versions are independent per module.)
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}
