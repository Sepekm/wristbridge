package dev.wristbridge.data

import android.content.Context
import android.content.SharedPreferences
import dev.wristbridge.relay.SendQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * All user configuration, held in one place.
 *
 * Reads happen on the notification hot path, so values are cached in memory and
 * only written through this object; [state] re-emits after every write so the
 * Compose UI stays in sync without observing SharedPreferences directly.
 */
class Settings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wristbridge", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<Snapshot> = _state

    data class Snapshot(
        val account: String,
        val hasPassword: Boolean,
        val destination: String,
        val relayEnabled: Boolean,
        val relayedPackages: Set<String>,
        val minQuietSeconds: Int,
        val maxPerHour: Int,
        val maxPerDay: Int,
        val includeOngoing: Boolean,
        val respectLocalOnly: Boolean,
        val replyChannelEnabled: Boolean,
        val replyPollSeconds: Int,
        val bleLinkEnabled: Boolean,
    ) {
        /** The address mail is delivered to; defaults to the account itself. */
        val effectiveDestination: String get() = destination.ifBlank { account }

        val isConfigured: Boolean
            get() = account.isNotBlank() && hasPassword && effectiveDestination.isNotBlank()
    }

    val snapshot: Snapshot get() = _state.value

    /** Decrypted on demand rather than held in the snapshot, to keep it out of UI state. */
    fun password(): String? = SecureStore.decrypt(prefs.getString(KEY_PASSWORD, null))

    fun setAccount(value: String) = edit { putString(KEY_ACCOUNT, value.trim()) }

    fun setPassword(value: String) = edit {
        // Apple prints app-specific passwords in hyphenated groups; the SMTP
        // server wants them without separators, and pasting them with the
        // hyphens is the single most common setup mistake.
        val normalized = value.replace("-", "").replace(" ", "")
        if (normalized.isBlank()) remove(KEY_PASSWORD)
        else putString(KEY_PASSWORD, SecureStore.encrypt(normalized))
    }

    /**
     * Removes the stored credential and destroys the Keystore key that
     * sealed it, so revoking the app-specific password on Apple's side has
     * a counterpart here.
     */
    fun forgetPassword() {
        edit { remove(KEY_PASSWORD) }
        SecureStore.clear()
    }

    fun setDestination(value: String) = edit { putString(KEY_DESTINATION, value.trim()) }

    fun setRelayEnabled(value: Boolean) = edit { putBoolean(KEY_ENABLED, value) }

    fun setPackageRelayed(packageName: String, relayed: Boolean) = edit {
        val current = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        if (relayed) current += packageName else current -= packageName
        putStringSet(KEY_PACKAGES, current)
    }

    fun setMinQuietSeconds(value: Int) = edit { putInt(KEY_QUIET, value.coerceIn(0, 3600)) }

    fun setMaxPerHour(value: Int) = edit { putInt(KEY_MAX_PER_HOUR, value.coerceIn(1, 500)) }

    /** Capped at Apple's published daily ceiling; see [dev.wristbridge.relay.SendQuota]. */
    fun setMaxPerDay(value: Int) = edit { putInt(KEY_MAX_PER_DAY, value.coerceIn(10, 900)) }

    fun setIncludeOngoing(value: Boolean) = edit { putBoolean(KEY_ONGOING, value) }

    fun setRespectLocalOnly(value: Boolean) = edit { putBoolean(KEY_LOCAL_ONLY, value) }

    fun setReplyChannelEnabled(value: Boolean) = edit { putBoolean(KEY_REPLY, value) }

    fun setBleLinkEnabled(value: Boolean) = edit { putBoolean(KEY_BLE, value) }

    fun setReplyPollSeconds(value: Int) = edit {
        putInt(KEY_REPLY_POLL, value.coerceIn(15, 900))
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = readSnapshot()
    }

    private fun readSnapshot() = Snapshot(
        account = prefs.getString(KEY_ACCOUNT, "").orEmpty(),
        hasPassword = !prefs.getString(KEY_PASSWORD, null).isNullOrBlank(),
        destination = prefs.getString(KEY_DESTINATION, "").orEmpty(),
        relayEnabled = prefs.getBoolean(KEY_ENABLED, false),
        relayedPackages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet(),
        minQuietSeconds = prefs.getInt(KEY_QUIET, DEFAULT_QUIET_SECONDS),
        maxPerHour = prefs.getInt(KEY_MAX_PER_HOUR, DEFAULT_MAX_PER_HOUR),
        maxPerDay = prefs.getInt(KEY_MAX_PER_DAY, SendQuota.DEFAULT_MAX_PER_DAY),
        includeOngoing = prefs.getBoolean(KEY_ONGOING, false),
        respectLocalOnly = prefs.getBoolean(KEY_LOCAL_ONLY, true),
        replyChannelEnabled = prefs.getBoolean(KEY_REPLY, false),
        replyPollSeconds = prefs.getInt(KEY_REPLY_POLL, DEFAULT_REPLY_POLL_SECONDS),
        bleLinkEnabled = prefs.getBoolean(KEY_BLE, false),
    )

    companion object {
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_QUIET = "quiet_seconds"
        private const val KEY_MAX_PER_HOUR = "max_per_hour"
        private const val KEY_MAX_PER_DAY = "max_per_day"
        private const val KEY_ONGOING = "include_ongoing"
        private const val KEY_LOCAL_ONLY = "respect_local_only"
        private const val KEY_REPLY = "reply_channel"
        private const val KEY_REPLY_POLL = "reply_poll_seconds"
        private const val KEY_BLE = "ble_link"

        /**
         * How often to check iCloud for replies. Every poll is a TLS handshake,
         * so this trades battery against how long a reply sits before sending.
         */
        const val DEFAULT_REPLY_POLL_SECONDS = 60

        /**
         * Chat apps repost a notification on every incoming message in a thread.
         * Collapsing repeats of the same conversation inside a short window is
         * what keeps the inbox (and the wrist) usable.
         */
        const val DEFAULT_QUIET_SECONDS = 20

        /** A ceiling that protects against a misbehaving app emptying the battery. */
        const val DEFAULT_MAX_PER_HOUR = 60

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
