package dev.wristbridge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the one secret this app holds, the iCloud app-specific password, in an
 * AES-GCM key that lives in the Android Keystore and never leaves it.
 *
 * Preferences on their own are readable by anything with the app's data
 * directory (a backup, a rooted shell), so the ciphertext is what gets stored
 * and the key stays hardware-backed where the device offers it.
 */
object SecureStore {

    private const val KEY_ALIAS = "wristbridge.smtp.credential"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Store as iv:ciphertext so the random IV travels with the payload.
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /**
     * Returns null rather than throwing when the stored value cannot be read,
     * which happens legitimately if the keystore key was lost (app data cleared,
     * device restored). The UI treats that as "not configured yet".
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            val (ivPart, dataPart) = stored.split(":", limit = 2).let { it[0] to it[1] }
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            require(iv.size == IV_LENGTH) { "unexpected IV length" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Synchronized because two threads arriving here at once would each
     * generate a key under the same alias, and the second would silently
     * replace the first, leaving whatever the first encrypted undecryptable.
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // StrongBox puts the key in a separate tamper-resistant chip, which on
        // a Pixel is the Titan M. Not every device has one, and asking for it
        // there throws, so the ordinary keystore remains the fallback.
        generateKey(useStrongBox = true)?.let { return it }
        return generateKey(useStrongBox = false)
            ?: error("Could not create a key to protect the stored password")
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey? = runCatching {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Notifications arrive while the screen is off, so the key must
                // be usable without the user authenticating first.
                .setUserAuthenticationRequired(false)
                .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
                .build()
        )
        generator.generateKey()
    }.getOrNull()

    /** Unused parameter kept so callers can pass a context uniformly. */
    @Suppress("UNUSED_PARAMETER")
    fun warmUp(context: Context) {
        runCatching { secretKey() }
    }
}
