package com.onelo.android

data class OneloConfig(
    /** Publishable key from Onelo dashboard (onelo_pk_live_...) */
    val publishableKey: String,
    /** Onelo API base URL — required. Get this from your Onelo dashboard snippet. */
    val apiUrl: String,
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
)
