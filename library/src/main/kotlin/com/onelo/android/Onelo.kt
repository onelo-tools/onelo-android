package com.onelo.android

import android.content.Context

class Onelo(config: OneloConfig, context: Context) {
    val auth: OneloAuth = OneloAuth(config, context)
}
