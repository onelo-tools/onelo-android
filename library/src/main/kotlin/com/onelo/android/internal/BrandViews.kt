package com.onelo.android.internal

import android.content.res.ColorStateList
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.onelo.android.OneloBrandChrome

/**
 * Applies [OneloBrandChrome] (resolved from the app's brand tokens, #681) to the
 * Activities' programmatic views — the only place they get a colour.
 */
internal fun OneloBrandChrome.paintGround(vararg views: View) {
    views.forEach { it.setBackgroundColor(pageBg) }
}

/** Progress bar: brand arc on the token track. */
internal fun OneloBrandChrome.tint(progressBar: ProgressBar) {
    val arc = ColorStateList.valueOf(brand)
    progressBar.indeterminateTintList = arc
    progressBar.progressTintList = arc
    progressBar.progressBackgroundTintList = ColorStateList.valueOf(spinnerTrack)
}

/** The fail-safe ✕ glyph: the brand text colour, readable on light and dark grounds. */
internal fun OneloBrandChrome.tintCloseGlyph(close: TextView) {
    close.setTextColor(text)
}
