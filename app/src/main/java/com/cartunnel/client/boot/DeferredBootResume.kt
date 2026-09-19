package com.cartunnel.client.boot

import android.content.Context

/** Persists only the fact that boot recovery was deferred until credentials unlock. */
class DeferredBootResume(
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    fun mark() = write(true)

    fun clear() = write(false)

    fun consume(): Boolean {
        if (!read()) return false
        write(false)
        return true
    }

    constructor(context: Context) : this(
        read = {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING, false)
        },
        write = { pending ->
            check(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PENDING, pending).commit(),
            )
        },
    )

    private companion object {
        const val PREFS = "cartunnel.resume"
        const val KEY_PENDING = "deferredBootResume"
    }
}
