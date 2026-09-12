package dev.wristbridge.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Date

/**
 * A small in-memory record of what the relay did, so the app can answer the
 * only question that matters while you are setting it up: did that notification
 * actually reach the watch, and if not, why not.
 *
 * Deliberately not persisted: it holds notification contents, and there is no
 * reason for that to outlive the process.
 */
object RelayLog {

    private const val CAPACITY = 60

    enum class Outcome { SENT, FAILED, SKIPPED }

    data class Entry(
        val at: Date,
        val outcome: Outcome,
        val appLabel: String,
        val summary: String,
        val detail: String? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val _sentCount = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = _sentCount

    /**
     * Called from both the notification callback thread and the sender
     * coroutine, so the updates go through [MutableStateFlow.update] rather
     * than a read-modify-write on `value`.
     */
    fun record(outcome: Outcome, appLabel: String, summary: String, detail: String? = null) {
        val entry = Entry(Date(), outcome, appLabel, summary, detail)
        _entries.update { (listOf(entry) + it).take(CAPACITY) }
        if (outcome == Outcome.SENT) _sentCount.update { it + 1 }
    }

    fun clear() {
        _entries.value = emptyList()
        _sentCount.value = 0
    }
}
