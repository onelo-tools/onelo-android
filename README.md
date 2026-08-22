# onelo-android

The Onelo SDK for Android, written in Kotlin.

Part of [Onelo](https://onelo.tools): hosted sign-in, a paywall on **your own Stripe** account, plan-gated feature flags, uptime monitoring, in-app feedback, a public roadmap and a waitlist — one SDK, wired together.

## Install

Published to **Maven Central**, so no extra repository line is needed — `mavenCentral()` is already in a standard Android project.

```kotlin
// build.gradle.kts (app/module)
implementation("tools.onelo:onelo-android:1.+")
```

## Quick start

Create the client once — in `Application.onCreate` or a singleton:

```kotlin
import com.onelo.android.Onelo
import com.onelo.android.OneloConfig

val onelo = Onelo(
    config = OneloConfig(
        publishableKey = "onelo_pk_live_YOUR_KEY",
        apiUrl = "https://api.onelo.tools",
    ),
    context = applicationContext,
)
```

`publishableKey` and `apiUrl` are both required. Your Onelo dashboard shows the snippet with your values already filled in.

### Sign-in

Register the launcher in `onCreate`, **before any UI interaction**, then trigger sign-in when you need it:

```kotlin
// 1. Register the launcher once, in your Activity's onCreate
val launcher = onelo.auth.registerLauncher(this) { session ->
    if (session != null) {
        // user signed in
    }
}

// 2. Trigger sign-in — the hosted page opens in a WebView
lifecycleScope.launch {
    onelo.auth.loadAuthView(launcher)
}
```

One nuance worth knowing: if the user is already authorized, `loadAuthView` returns a session directly and **nothing is launched** — the launcher callback never fires. A `null` return means a screen was opened and the result will arrive through the launcher instead.

### Deep links (required for most flows)

Magic links, social sign-in and the Access Gate all return to your app through a custom URL scheme. Set `callbackScheme` in `OneloConfig`, declare the intent filter, and forward the redirect:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="myapp" />
</intent-filter>
```

```kotlin
// Call in BOTH onCreate and onNewIntent
onelo.handleRedirect(intent.data)
```

Only plain email-and-password sign-in works without this.

## Modules

Everything below hangs off the one `onelo` instance.

| Accessor | What it does | Key methods |
|---|---|---|
| `onelo.auth` | Hosted sign-in and sessions | `registerLauncher()`, `loadAuthView()`, `getSession()`, `whenReady()` |
| `onelo.features` | Plan-gated feature flags | `declare()`, `feature()`, `ready()` |
| `onelo.monitor` | Error and event reporting | `event()`, `track()`, `capture()`, `setUserId()` |
| `onelo.paywall` | Your hosted store, on your own Stripe | `initiateStoreFlow()`, `cancelSubscription()` |
| `onelo.customerPortal` | Cancel, change plan, refunds, invoices | `registerLauncher()`, `openCustomerPortal()` |
| `onelo.feedback` | In-app bug reports and feature requests | `registerLauncher()`, `open()` |
| `onelo.forms` | Form submissions | `submit()` |
| `onelo.waitlist` | Pre-launch signups | `join()` |
| `onelo.consent` | Versioned terms / privacy consent gate | `registerLauncher()`, `requiredConsents()`, `acceptConsent()` |

A feature flag is more than on/off — the handle exposes the state your UI should render:

```kotlin
val f = onelo.features.feature("export-button")

when {
    f.isEnabled()     -> showExportButton()
    f.isUpsell()      -> showUpgradePrompt()
    f.isComingSoon()  -> showComingSoonBadge()
    f.isGreyed()      -> showDisabledButton()
}
```

## Security

- **Tokens are stored in `EncryptedSharedPreferences`** (AES256-SIV keys, AES256-GCM values) — never plain SharedPreferences.
- **Play Integrity** is supported. When your app is configured to require attestation, the SDK fetches a Play Integrity token and attaches it to requests automatically — there is no API to call.

## Requirements

- **minSdk 24**, compiled against SDK 34
- **Java 17** / Kotlin JVM target 17
- Uses AndroidX (AppCompat, Activity KTX, Security Crypto) and Kotlin coroutines

## Links

- **Docs:** [onelo.tools/docs](https://onelo.tools/docs)
- **Dashboard:** [onelo.tools](https://onelo.tools) — your app's snippet comes pre-filled with your keys
- **Issues:** please report them on this repository

## License

MIT
