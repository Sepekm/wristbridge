package dev.wristbridge.health

import android.content.Context
import dev.wristbridge.ble.BleProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Holds the health samples the watch has sent.
 *
 * This is the half of the bridge that only exists once code is running on the
 * watch: HealthKit is the sole route to heart rate, sleep and workout data, and
 * nothing on the Android side can reach it.
 *
 * Samples are kept on disk so the history survives a restart, but capped — this
 * is a bridge, not a health database. Anything long-term belongs in Health
 * Connect, which [HealthConnectWriter] forwards to.
 */
class HealthStore private constructor(context: Context) {

    /** The sample kinds the watch app sends, matching HKQuantityTypeIdentifier names. */
    object Kind {
        const val HEART_RATE = "heartRate"
        const val STEPS = "steps"
        const val ACTIVE_ENERGY = "activeEnergy"
        const val RESTING_HEART_RATE = "restingHeartRate"
        const val OXYGEN_SATURATION = "oxygenSaturation"
        const val SLEEP = "sleep"
        const val WORKOUT = "workout"
        const val STAND_HOURS = "standHours"
        const val EXERCISE_MINUTES = "exerciseMinutes"

        /** A readable label, since these ids surface directly in the UI. */
        fun label(kind: String): String = when (kind) {
            HEART_RATE -> "Heart rate"
            STEPS -> "Steps"
            ACTIVE_ENERGY -> "Active energy"
            RESTING_HEART_RATE -> "Resting heart rate"
            OXYGEN_SATURATION -> "Blood oxygen"
            SLEEP -> "Sleep"
            WORKOUT -> "Workout"
            STAND_HOURS -> "Stand hours"
            EXERCISE_MINUTES -> "Exercise minutes"
            else -> kind.replaceFirstChar(Char::uppercase)
        }
    }

    data class Sample(
        val kind: String,
        val value: Double,
        val unit: String,
        val start: Long,
        val end: Long,
    ) {
        val startedAt: Date get() = Date(start)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("wristbridge.health", Context.MODE_PRIVATE)

    private val _samples = MutableStateFlow(load())
    val samples: StateFlow<List<Sample>> = _samples

    /** Most recent value per kind, which is what the summary screen shows. */
    val latestByKind: Map<String, Sample>
        get() = _samples.value.groupBy { it.kind }.mapValues { (_, list) -> list.maxBy { it.start } }

    fun record(incoming: List<BleProtocol.HealthSample>) {
        if (incoming.isEmpty()) return
        val mapped = incoming.map {
            Sample(kind = it.kind, value = it.value, unit = it.unit, start = it.start, end = it.end)
        }
        _samples.update { existing ->
            // The watch re-sends anything it is unsure landed, so drop exact
            // repeats rather than double-counting steps or energy.
            val seen = existing.mapTo(HashSet()) { it.kind to it.start }
            val fresh = mapped.filterNot { (it.kind to it.start) in seen }
            (existing + fresh).sortedByDescending { it.start }.take(CAPACITY)
        }
        persist()
    }

    fun clear() {
        _samples.value = emptyList()
        prefs.edit().remove(KEY_SAMPLES).apply()
    }

    private fun persist() {
        val array = JSONArray()
        for (sample in _samples.value) {
            array.put(
                JSONObject()
                    .put("k", sample.kind)
                    .put("v", sample.value)
                    .put("u", sample.unit)
                    .put("s", sample.start)
                    .put("e", sample.end)
            )
        }
        prefs.edit().putString(KEY_SAMPLES, array.toString()).apply()
    }

    private fun load(): List<Sample> = runCatching {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Sample(
                kind = item.optString("k"),
                value = item.optDouble("v", 0.0),
                unit = item.optString("u"),
                start = item.optLong("s"),
                end = item.optLong("e"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_SAMPLES = "samples"
        private const val CAPACITY = 2000

        @Volatile
        private var instance: HealthStore? = null

        fun get(context: Context): HealthStore =
            instance ?: synchronized(this) {
                instance ?: HealthStore(context).also { instance = it }
            }
    }
}
