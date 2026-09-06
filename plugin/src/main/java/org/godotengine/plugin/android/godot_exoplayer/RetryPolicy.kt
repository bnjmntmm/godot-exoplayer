package org.godotengine.plugin.android.godot_exoplayer

/** Pure policy, separate from Android scheduling and player ownership. */
internal data class RetryPolicy(val maxAttempts: Int = 0, val baseDelayMs: Long = 1000,
    val maxDelayMs: Long = 30000, val liveAtDefaultPosition: Boolean = true) {
    init {
        require(maxAttempts in 0..20) { "maxAttempts must be between 0 and 20" }
        require(baseDelayMs in 100..300000 && maxDelayMs in baseDelayMs..300000) { "Invalid retry delays" }
    }
    fun delayMs(attempt: Int): Long = (baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 20))).coerceAtMost(maxDelayMs)
    companion object {
        fun isTransient(code: Int, httpStatus: Int): Boolean = when {
            httpStatus != 0 -> httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599
            else -> code == 1002 || code == 2001 || code == 2002 // behind-live-window, connection, timeout
        }
    }
}
