package com.onelo.android.internal

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pad [view]'s top by the system status bar inset, so hosted-page content (which
 * positions its own close ✕ with a fixed CSS offset, unaware of any real device
 * chrome) never renders underneath it.
 *
 * A host app targeting Android 15 (API 35)+ gets edge-to-edge enforced by the OS
 * REGARDLESS of this library's own `Theme.AppCompat.NoActionBar` — that enforcement
 * is keyed off the CONSUMING app's targetSdk, not this library's. Before this, the
 * WebView filled the full window including the status-bar strip, and the hosted
 * page's `HostedCloseX` (fixed `top: 12`) rendered directly under the system clock/
 * battery icons — reported 2026-08-21 on the store flow (`OneloAuthActivity`).
 *
 * Deliberately generic (not tied to one Activity): [WindowInsetsCompat] reports
 * ZERO for `systemBars()` when the window is NOT edge-to-edge (the OS already
 * reserves the space, non-edge-to-edge legacy behaviour), so this is a no-op on
 * pre-35 targets and only pads when the OS is actually drawing behind the bar —
 * never double-offsets.
 */
internal fun applyStatusBarInsetPadding(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
        insets
    }
}
