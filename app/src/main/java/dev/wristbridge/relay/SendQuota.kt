package dev.wristbridge.relay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * Keeps the relay inside iCloud's daily sending allowance.
 *
 * Apple caps an iCloud account at 1,000 outbound messages a day and soft-blocks
 * accounts that behave like bulk senders. A chatty phone can reach that without
 * trying, and the consequence lands on the user's actual email rather than on
 * this app, so the ceiling is enforced here, deliberately well below Apple's.
 *
 * The count is persisted: a process restart must not hand out a fresh day's
 * worth of sends, which is exactly what an in-memory counter would do.
 */
class SendQuota private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wristbridge.quota", Context.MODE_PRIVATE)

    private val _used = MutableStateFlow(0)

    /** Messages sent so far today, resetting at local midnight. */
    val usedToday: StateFlow<Int> = _used

    init {
        _used.value = currentCount()
    }

    /**
     * Reserves one send if the day's allowance has room.
     * Returns false when the cap is reached, which the caller surfaces rather
     * than silently dropping.
     */
    @Synchronized
    fun tryConsume(limit: Int): Boolean {
        val today = dayStamp()
        val count = if (prefs.getInt(KEY_DAY, -1) == today) prefs.getInt(KEY_COUNT, 0) else 0
        if (count >= limit) {
            _used.value = count
            return false
        }
        prefs.edit().putInt(KEY_DAY, today).putInt(KEY_COUNT, count + 1).apply()
        _used.value = count + 1
        return true
    }

    /** Hands back an unused reservation when the send ultimately failed. */
    @Synchronized
    fun refund() {
        if (prefs.getInt(KEY_DAY, -1) != dayStamp()) return
        val count = (prefs.getInt(KEY_COUNT, 0) - 1).coerceAtLeast(0)
        prefs.edit().putInt(KEY_COUNT, count).apply()
        _used.value = count
    }

    @Synchronized
    fun remaining(limit: Int): Int = (limit - currentCount()).coerceAtLeast(0)

    private fun currentCount(): Int =
        if (prefs.getInt(KEY_DAY, -1) == dayStamp()) prefs.getInt(KEY_COUNT, 0) else 0

    /** Days since the epoch in local time, so the window turns over at midnight. */
    private fun dayStamp(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"

        /**
         * Apple's published ceiling. Documented here so the reason for the
         * default below is not a mystery later.
         */
        const val ICLOUD_DAILY_LIMIT = 1000

        /**
         * Deliberately well under Apple's limit: the same account sends your
         * real email, and tripping a soft block would take that down with it.
         */
        const val DEFAULT_MAX_PER_DAY = 300

        @Volatile
        private var instance: SendQuota? = null

        fun get(context: Context): SendQuota =
            instance ?: synchronized(this) {
                instance ?: SendQuota(context).also { instance = it }
            }
    }
}
