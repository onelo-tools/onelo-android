package com.onelo.android

data class OneloConfig(
    /** Publishable key from Onelo dashboard (onelo_pk_live_...) */
    val publishableKey: String,
    /** Onelo API base URL — required. Get this from your Onelo dashboard snippet. */
    val apiUrl: String,
)
