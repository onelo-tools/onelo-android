package com.onelo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android SDK carries its version TWICE:
 *
 *   1. `library/build.gradle.kts` → `version = "…"` — the Maven coordinate.
 *      `_publish-maven-central.yml` uploads `tools.onelo:onelo-android:<this>`
 *      straight from the private staging repo, so this string IS the published
 *      artifact's version.
 *   2. `OneloSdkVersion.kt` → `SDK_VERSION` — what the SDK puts on the wire
 *      (`X-Sdk-Version` on every request, `sdk.version` in the monitor payload)
 *      and what the dashboard's outdated-SDK warning compares.
 *
 * Nothing derives one from the other, and a comment is not a mechanism — they
 * drifted to `1.41.0-staging` vs `1.41.0` and nothing caught it (#103).
 *
 * The rule enforced here is **byte-identical, suffix-free strings**, not merely
 * "same base version". Two independent facts about the release machinery force
 * that stricter rule:
 *
 *   • `_publish-maven-central.yml` publishes `build.gradle.kts`'s version
 *     VERBATIM — its own comment says "already the clean release version — JVM
 *     SDK sources carry no -staging suffix". A suffix there would ship
 *     `tools.onelo:onelo-android:1.41.0-staging` to Maven Central.
 *   • Until #110, `_release-sdk.yml` stripped `-staging` from exactly ONE file
 *     per SDK (an if/elif chain) — for Android `library/build.gradle.kts`, never
 *     this file — so a suffix here would have survived into the PUBLIC production
 *     release and made prod traffic report itself as staging (the failure that
 *     forced the `dist/` version rewrite for the JS SDKs, public tags v0.36.1 /
 *     v0.36.2, 2026-08-03). #110 replaced that chain with a per-SDK file list
 *     (`scripts/sdk-release-rewrite.sh`) that rewrites this file as well and
 *     fails the release if any `X.Y.Z-staging` survives. The Maven Central path
 *     rewrites nothing, so the first fact still bans the suffix on its own.
 *
 * So on this SDK the suffix convention does not apply at all: both strings are
 * clean semver, and they must match each other exactly.
 */
class SdkVersionSyncTest {

    /** `^version = "…"` in library/build.gradle.kts, located from the test's CWD. */
    private fun gradleVersion(): String {
        val buildFile = findBuildGradle()
        assertNotNull(
            "could not locate library/build.gradle.kts from ${File(".").absolutePath}",
            buildFile
        )
        val line = buildFile!!.readLines().firstOrNull { it.startsWith("version = \"") }
        assertNotNull("no `version = \"…\"` line in ${buildFile.path}", line)
        return line!!.substringAfter('"').substringBefore('"')
    }

    /**
     * Gradle sets `user.dir` to the module project dir, so the FIRST candidate is
     * normally the right file and the walk never iterates. It stays as
     * belt-and-braces for a runner that starts elsewhere.
     *
     * The match is anchored on the module's own Maven coordinate (`"onelo-android"`,
     * from the `coordinates(...)` call) as well as the version line. To be precise
     * about what that buys: NO file in this repo today could satisfy the version
     * line alone and be wrong. The walk only ascends parents and only ever tests
     * `<dir>/build.gradle.kts` and `<dir>/library/build.gradle.kts`, so it cannot
     * reach a sibling module; `packages/onelo-android/build.gradle.kts` is a
     * plugins-only block with no version line; it is the only Gradle module under
     * `packages/`; and there is no root or `packages/` build script.
     *
     * So the anchor fixes no live bug. It is here because the alternative predicate
     * is "any file whose first `version = "…"` line I happen to find", which is
     * true of a shape rather than of an identity: it would start matching the wrong
     * thing the day someone adds a root `build.gradle.kts` or another Gradle
     * module, and it would do so silently, by comparing this SDK's
     * constant against another module's manifest. Asserting identity costs one
     * `lines.any {}` and removes that future.
     */
    private fun findBuildGradle(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            for (candidate in listOf(File(d, "build.gradle.kts"), File(d, "library/build.gradle.kts"))) {
                if (!candidate.isFile) continue
                val lines = candidate.readLines()
                val isThisModule = lines.any { it.contains("\"onelo-android\"") }
                if (isThisModule && lines.any { it.startsWith("version = \"") }) {
                    return candidate
                }
            }
            dir = d.parentFile
        }
        return null
    }

    @Test
    fun `SDK_VERSION matches the gradle version exactly`() {
        assertEquals(
            "OneloSdkVersion.kt SDK_VERSION and library/build.gradle.kts `version` " +
                "have drifted. They must be the SAME string — see this file's header.",
            gradleVersion(),
            SDK_VERSION
        )
    }

    @Test
    fun `both versions are clean semver with no pre-release suffix`() {
        val semver = Regex("""^\d+\.\d+\.\d+$""")
        assertTrue(
            "library/build.gradle.kts version `${gradleVersion()}` is not clean semver. " +
                "It is published to Maven Central verbatim, so it must carry no suffix.",
            semver.matches(gradleVersion())
        )
        assertTrue(
            "SDK_VERSION `$SDK_VERSION` is not clean semver. The Maven Central publish " +
                "ships this SDK's version verbatim, so a suffix here would reach " +
                "production and mislabel prod telemetry as staging.",
            semver.matches(SDK_VERSION)
        )
    }
}
