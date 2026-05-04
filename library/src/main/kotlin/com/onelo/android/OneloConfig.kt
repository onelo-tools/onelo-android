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
)
