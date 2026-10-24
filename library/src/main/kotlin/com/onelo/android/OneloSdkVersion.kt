package com.onelo.android

// The Maven coordinate in `library/build.gradle.kts` (`version = "…"`) and this
// constant must be the SAME, suffix-free string — SdkVersionSyncTest enforces it.
// No `-staging` suffix on this SDK: build.gradle.kts is published to Maven Central
// verbatim by _publish-maven-central.yml, which rewrites nothing. (The prod
// release, _release-sdk.yml, does rewrite this file too since #110 — see
// scripts/sdk-release-rewrite.sh — but Maven Central is the channel that ships
// the string as-is, so the suffix stays banned.)
internal const val SDK_VERSION = "1.57.0"

// Registry key the dashboard compares SDK_VERSION against
// (`LATEST_SDK_VERSIONS["onelo-android"]`). Sent as `X-Onelo-Sdk-Name` next to
// `X-SDK-Version` (#122): before it, the dashboard guessed the SDK from the
// app's type and judged an Android install against onelo-swift.
internal const val SDK_NAME = "onelo-android"

/** The SDK's identity headers — version and name, always together. */
internal fun sdkIdentityHeaders(): Map<String, String> = mapOf(
    "X-SDK-Version" to SDK_VERSION,
    "X-Onelo-Sdk-Name" to SDK_NAME,
)
