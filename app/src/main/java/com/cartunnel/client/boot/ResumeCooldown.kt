package com.cartunnel.client.boot

import android.content.Context

class ResumeCooldown(
    private val read: () -> Long,
    private val write: (Long) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val intervalMs: Long = 5_000L,
) {
    fun tryAcquire(): Boolean {
        val timestamp = now()
        val previous = read()
        if (previous != Long.MIN_VALUE && timestamp - previous < intervalMs) return false
        write(timestamp)
        return true
    }

    constructor(context: Context) : this(
        read = {
            context.getSharedPreferences("cartunnel.resume", Context.MODE_PRIVATE)
                .getLong(KEY_LAST, Long.MIN_VALUE)
        },
        write = { timestamp ->
            check(
                context.getSharedPreferences("cartunnel.resume", Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST, timestamp).commit(),
            )
        },
    )

    private companion object {
        const val KEY_LAST = "lastResumeAt"
    }
}
