package com.cartunnel.client.ui

import android.content.Context

/** Installation-local acceptance; backup is disabled by the application. */
object UserConsent {
    private const val STORE = "cartunnel.consent"
    private const val ACCEPTED = "mit.accepted"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getBoolean(ACCEPTED, false)

    fun accept(context: Context): Boolean =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putBoolean(ACCEPTED, true).commit()
}
