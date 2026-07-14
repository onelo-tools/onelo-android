package com.onelo.android

data class OneloConfig(
    /** Publishable key from Onelo dashboard (onelo_pk_live_...) */
    val publishableKey: String,
    /** Onelo API base URL — required. Get this from your Onelo dashboard snippet. */
    val apiUrl: String,
    /**
     * Deep-link callback scheme registered in your AndroidManifest (e.g. "myapp").
     * Used for the hosted-auth, OAuth (Chrome Custom Tabs) and customer-portal
     * return deep links. Defaults to "oneloandroid" for backward compatibility;
     * set it to match your manifest's `<data android:scheme="...">` if you use
     * social sign-in or the customer portal.
     */
    val callbackScheme: String = "oneloandroid",
    /**
     * Optional deploy environment tag ("production" | "staging" | "dev"). When set
     * it surfaces as the "Environment" column in Monitor / Feature Health. When
     * null the field is omitted. Distinct from [featureEnvironment].
     */
    val environment: String? = null,
    /**
     * Suppresses the "no userId — call onelo.identify()" warning that fires when
     * features resolve in anonymous mode while targeted features exist. Set to true
     * if your app is intentionally anonymous. Default: false.
     */
    val suppressIdentifyWarning: Boolean = false,
    /**
     * Explicit Features environment selector ("test" | "live"). When set, it is
     * forwarded on every Features request and wins over the key prefix on the
     * backend. When null (default), the field is omitted and the backend falls
     * back to the publishable key prefix (backward compatible). No env-var
     * fallback on mobile — set this explicitly. Any value other than "test"/"live"
     * normalizes to null. See docs/architecture/feature-environment-explicit.md.
     */
    val featureEnvironment: String? = null,
    /**
     * When true (default), the SDK AUTOMATICALLY presents the legal-consent gate
     * whenever a blocking legal version is outstanding — on sign-in, on the live
     * `legal.consent_required` push, and on host-Activity resume. The host app
     * only needs to call [OneloConsent.registerLauncher] once. Parity with
     * Swift/Electron/Flutter/React Native (all auto-present).
     *
     * Set to false to drive the gate yourself: the SDK won't auto-present; use
     * [OneloConsent.requiredConsents] + [OneloConsent.consentRevisionFlow] and
     * call [OneloConsent.presentGateIfNeeded] on your own schedule.
     */
    val autoPresentConsentGate: Boolean = true,
    /**
     * Status returned by [OneloFeatures.feature] for any feature whose name isn't
     * (yet) in the resolved snapshot. Defaults to [FeatureStatus.HIDDEN] — fail-closed,
     * so a feature instrumented in code but not yet enabled in the dashboard stays
     * hidden in production. Set to [FeatureStatus.ENABLED] during development to have
     * new gates show their content until you toggle them in the dashboard. 1:1 with
     * Swift `Onelo(featureDefaultStatus:)`.
     */
    val featureDefaultStatus: FeatureStatus = FeatureStatus.HIDDEN,
    /**
     * When true (default), the Features module force-refreshes its snapshot whenever
     * the app returns to the FOREGROUND, so a dashboard Deploy that happened while the
     * app was backgrounded (SSE possibly dropped under Doze) reconciles immediately
     * instead of waiting up to the 60s poll. 1:1 with Swift `autoLifecycleRefresh`
     * (Swift's `_resyncOnLifecycle`). Set false to rely solely on the poll + SSE.
     */
    val autoLifecycleRefresh: Boolean = true,
)
