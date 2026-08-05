plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Publishing to Maven Central via the Central Portal. Auto-detects the Android
    // library platform and builds the release AAR + sources jar + javadoc jar +
    // signs + uploads — everything Central requires. Replaces the hand-rolled
    // maven-publish + GitHub Packages block.
    id("com.vanniktech.maven.publish")
}

// Maven coordinate = tools.onelo:onelo-android (reverse-DNS of onelo.tools, the
// namespace verified on central.sonatype.com). `version` is the line the Publish
// SDK / Release workflows grep, so keep the `version = "…"` shape.
group = "tools.onelo"
version = "1.33.0"

android {
    namespace = "com.onelo.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    // NB: no `android.publishing.singleVariant` here — the vanniktech plugin's
    // auto-detected AndroidSingleVariantLibrary configures the `release` variant +
    // sources jar itself. Declaring it here as well makes AGP throw "variant is
    // already published".
}

dependencies {
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.play:integrity:1.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

mavenPublishing {
    // Central Portal (the OSSRH successor). vanniktech 0.34.0's argument-less
    // publishToMavenCentral() already targets the Central Portal — the SonatypeHost
    // enum was removed by 0.34.0, so there is nothing to pass. Credentials + GPG come
    // from mavenCentralUsername/Password + signingInMemoryKey* (set in CI as
    // ORG_GRADLE_PROJECT_* env vars — never committed).
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "onelo-android", version.toString())

    pom {
        name.set("Onelo Android SDK")
        description.set("Official Onelo SDK for Android — auth, features, monitor, paywall, feedback, consent.")
        url.set("https://github.com/onelo-tools/onelo-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("onelo")
                name.set("Onelo")
                url.set("https://onelo.tools")
            }
        }
        scm {
            url.set("https://github.com/onelo-tools/onelo-android")
            connection.set("scm:git:git://github.com/onelo-tools/onelo-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/onelo-tools/onelo-android.git")
        }
    }
}
